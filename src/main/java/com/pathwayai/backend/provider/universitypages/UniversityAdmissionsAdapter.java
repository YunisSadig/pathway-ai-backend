package com.pathwayai.backend.provider.universitypages;

import com.pathwayai.backend.refresh.extract.ContextualScopedExtractionAdapter;
import com.pathwayai.backend.refresh.extract.ExtractedFact;
import com.pathwayai.backend.refresh.registry.SourceMonitor;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ResourceLoader;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** Shared official guidance is scoped by institution and entry level; bachelor and master rules never cross. */
@Component
public class UniversityAdmissionsAdapter implements ContextualScopedExtractionAdapter {
    public static final String NAME="university-admissions";
    public record SourceSpec(String university,String entryLevel,String purpose,String url,String heading) { }
    private final List<SourceSpec> supporting;
    private final CuratedSources candidates;
    private final JdbcClient jdbc;
    public UniversityAdmissionsAdapter(CuratedSources candidates,JdbcClient jdbc,ResourceLoader resources,
                                      @Value("${app.curated.supporting:classpath:reference-data/curated-supporting-sources.json}") String location) {
        this.candidates=candidates;this.jdbc=jdbc;
        try(InputStream in=resources.getResource(location).getInputStream()) {
            supporting=List.of(JsonMapper.builder().build().readValue(in,SourceSpec[].class));
        }catch(java.io.IOException e){throw new java.io.UncheckedIOException(e);}
        for(var source:supporting)if(!CuratedSources.owned(source.url(),source.university()))throw new IllegalArgumentException("Invalid supporting source");
    }
    @Override public String name(){return NAME;}
    @Override public String version(){return "1";}
    public List<SourceSpec> sources(){return supporting;}

    @Override public Map<String,List<ExtractedFact>> extractHtml(String html,String finalUrl,SourceMonitor monitor) {
        var source=supporting.stream().filter(s->s.url().equals(monitor.url())).findFirst().orElseThrow();
        String university=jdbc.sql("select slug from university where id=:u").param("u",monitor.universityId()).query(String.class).single();
        if(!source.university().equals(university))throw new IllegalArgumentException("Guidance source belongs to another university");
        Map<String,List<ExtractedFact>> result=new LinkedHashMap<>();
        for(var candidate:candidates.candidates()) {
            if(!applies(source,candidate))continue;
            var slug=jdbc.sql("select p.slug from degree_program p join program_external_id e on e.program_id=p.id where e.scheme='STUDYINFO_KOULUTUS_OID' and e.value=:oid")
                    .param("oid",candidate.educationOid()).query(String.class).optional();
            slug.ifPresent(s->result.put(s,extract(html,finalUrl,source,candidate)));
        }
        return result;
    }

    public static boolean applies(SourceSpec source,CuratedSources.Candidate candidate) {
        return source.university().equals(candidate.university()) && (source.entryLevel().equals("ALL") || source.entryLevel().equals(candidate.entryLevel()));
    }

    public static List<ExtractedFact> extract(String html,String finalUrl,SourceSpec source,CuratedSources.Candidate candidate) {
        if(!applies(source,candidate) || !CuratedSources.owned(finalUrl,source.university()))throw new IllegalArgumentException("Wrong institution or entry route");
        Document doc=Jsoup.parse(html,finalUrl);
        if(doc.select("h1").size()!=1 || !doc.select("h1").text().equals(source.heading()))throw new IllegalArgumentException("Supporting source identity changed");
        doc.select("nav,header,footer,aside,script,style").remove();
        Map<String,String> values=new LinkedHashMap<>();
        if(source.purpose().equals("ADMISSIONS")) {
            values.put("official_admissions_url",finalUrl);
            values.put("instructions",finalUrl);
            add(values,"eligibility",section(doc,"(?i)^(general )?eligibility( criteria| requirements)?$|^who can apply.*"));
            add(values,"documents",section(doc,"(?i).*(required documents|application documents|document checklist|documents required|attachments).*"));
            add(values,"selection",section(doc,"(?i)^(selection criteria|selection procedure|admission groups|how are applicants selected).*"));
            add(values,"entrance_tests",section(doc,"(?i)^(SAT|ACT|SAT and ACT|entrance exam|admission based on SAT).*"));
        }
        if(source.purpose().equals("LANGUAGE")) {
            String requirements=section(doc,"(?i)^(IELTS|TOEFL|accepted language tests|english language tests).*" );
            if(requirements!=null)values.put("language_tests",requirements);
            values.put("instructions",finalUrl);
        }
        if(source.purpose().equals("FINANCIAL")) {
            values.put("financial_url",finalUrl);
            add(values,"tuition_scope",section(doc,"(?i)^(who (is|required|needs|has|must)|tuition fee liability|exemptions from tuition|who pays).*"));
            add(values,"scholarship",section(doc,"(?i)^(aalto university excellence scholarships|scholarships|scholarship programme|tuition waivers).*"));
        }
        String route=candidate.entryLevel().equals("BACHELOR")?"bachelor":"master";
        if(source.purpose().equals("ELIGIBILITY"))
            add(values,"eligibility",section(doc,"(?i)^Eligibility for "+route+".*"));
        if(source.purpose().equals("DOCUMENTS"))
            add(values,"documents",section(doc,"(?i)^Applying for a "+route+".*"));
        if(source.purpose().equals("SAT")) {
            String programme=candidate.heading().replaceFirst("^Bachelor's in ","");
            List<String> requirements=new ArrayList<>();
            for(Element table:doc.select("table"))for(Element row:table.select("tr"))
                if(row.text().toLowerCase(java.util.Locale.ROOT).contains(programme.toLowerCase(java.util.Locale.ROOT)))
                    requirements.add(table.select("th").text()+"\n"+row.text());
            String common=section(doc,"(?i)^Important information about SAT results$");
            if(common!=null)requirements.add(common);
            if(!requirements.isEmpty())values.put("entrance_tests",String.join("\n\n",requirements));
        }
        List<ExtractedFact> facts=new ArrayList<>();
        String scope=source.purpose().toLowerCase(java.util.Locale.ROOT);
        for(var e:values.entrySet()) facts.add(ExtractedFact.of(ExtractedFact.FactType.UNIVERSITY_FACT,e.getKey()+"|"+scope,
                Map.of("field",e.getKey(),"scope",scope,"value",e.getValue(),"educationOid",candidate.educationOid())));
        return facts;
    }

    static String section(Document doc,String regex) {
        Pattern pattern=Pattern.compile(regex);
        List<String> sections=new ArrayList<>();
        for(Element h:doc.select("h2,h3,h4"))if(pattern.matcher(h.text()).matches()) {
            int level=Integer.parseInt(h.tagName().substring(1));
            StringBuilder text=new StringBuilder(h.text());
            for(Element e=h.nextElementSibling();e!=null;e=e.nextElementSibling()) {
                if(e.tagName().matches("h[1-6]") && Integer.parseInt(e.tagName().substring(1))<=level)break;
                if(!e.text().isBlank())text.append("\n").append(e.text());
                if(text.length()>6000)break;
            }
            if(text.length()>h.text().length() && text.length()<=6000)sections.add(text.toString());
        }
        String combined=String.join("\n\n",sections);
        return combined.isBlank() || combined.length()>10000 ? null:combined;
    }
    private static void add(Map<String,String> values,String field,String value){if(value!=null)values.put(field,value);}
}
