package com.pathwayai.backend.provider.universitypages;

import com.pathwayai.backend.refresh.extract.ExtractedFact;
import com.pathwayai.backend.refresh.extract.ExtractionAdapter;
import com.pathwayai.backend.refresh.extract.PageCanonicalizer.CanonicalPage;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Reads the short fact box of a reviewed university program page. One shared extractor; each university only
 * contributes where its fact box is ({@link Profile}) and what its labels say. Every fact is bound to the reviewed
 * education OID. Only short values are kept (at most {@value #MAX_VALUE} characters): the page itself stays the place
 * for prose. A fee is never given an academic year here, because none of these pages states one next to the fee.
 */
@Component
public class UniversityProgramAdapter implements ExtractionAdapter {
    public static final String NAME="university-program";
    public static final String VERSION="2";
    static final int MAX_VALUE=400;

    /**
     * {@code container}/{@code label}: CSS selectors of one fact-box entry and of its label inside it; the value is the
     * entry's remaining text. {@code labels}: fact field -> exact label texts. {@code programPaths}: the URL path prefixes
     * of program pages. {@code statesPathway}: the qualification line names every degree of a combined pathway.
     */
    record Profile(String container,String label,Map<String,List<String>> labels,List<String> programPaths,boolean statesPathway) { }

    static final Map<String,Profile> PROFILES=Map.of(
            "aalto-university",new Profile(".aalto-feature-item--text-container","h3",Map.of(
                    "qualification",List.of("Degree:"),"language",List.of("Language of instruction:"),"credits",List.of("Credits:"),
                    "duration_structure",List.of("Duration:"),"eligibility",List.of("Eligibility:"),
                    "application_period",List.of("Application period:"),"tuition_scope",List.of("Tuition fees:")),
                    List.of("/en/study-options/"),true),
            "university-of-oulu",new Profile(".info-box__item","h3",Map.of(
                    "qualification",List.of("Degree title"),"language",List.of("Language of instruction"),"credits",List.of("Scope"),
                    "duration_structure",List.of("Duration of studies"),"study_mode",List.of("Teaching mode"),
                    "application_period",List.of("Joint application period","Next application period"),
                    "tuition_scope",List.of("Tuition fee"),"scholarship",List.of("Scholarship")),
                    List.of("/en/apply/bachelors-","/en/apply/masters-"),true),
            "university-of-helsinki",new Profile(".degree-programme__factbox--item",".degree-programme__factbox--item__label",Map.of(
                    "qualification",List.of("Degree level"),"language",List.of("Language"),
                    "credits",List.of("Scope of studies and length"),
                    "tuition_scope",List.of("Tuition fee per year (only non-EU/EEA citizens)")),
                    List.of("/en/degree-programmes/"),false),
            "tampere-university",new Profile("div:has(> h2)","h2",Map.of(
                    "qualification",List.of("Degree earned"),"duration_structure",List.of("Planned duration"),
                    "credits",List.of("Extent of studies"),"tuition_scope",List.of("Tuition fee for non-EU/EEA citizens")),
                    List.of("/en/tau/bachelors-programmes/","/en/tau/masters-programmes/"),true),
            "lut-university",new Profile(".info-box__item",".info-box__item-label",Map.of(
                    "qualification",List.of("Degree"),"language",List.of("Language"),"credits",List.of("Credits"),
                    "duration_structure",List.of("Duration"),"study_mode",List.of("Form of study"),
                    "application_period",List.of("Application period"),"tuition_scope",List.of("Fees for non-EU/EEA students")),
                    List.of("/en/studies/"),false),
            "university-of-turku",new Profile("div.field:has(> h2.education-card__heading)","h2.education-card__heading",Map.of(
                    "qualification",List.of("Qualifications"),"language",List.of("Language"),
                    "credits",List.of("Extent of the study programme"),"duration_structure",List.of("Planned duration"),
                    "study_mode",List.of("Mode of teaching"),"tuition_scope",List.of("Tuition fee")),
                    List.of("/en/study-at-utu/"),true),
            // Jyväskylä has no fact box: the fee is a heading "Tuition fee €N" followed by its audience (see feeHeading).
            "university-of-jyvaskyla",new Profile("none","none",Map.of(),
                    List.of("/en/study-with-us/bachelors-degree-programmes/","/en/study-with-us/masters-degree-programmes/"),false));

    private static final Pattern FEE_HEADING=Pattern.compile("^Tuition fee (€\\s?\\d{1,3}(?:[ ,]\\d{3})*)$");

    private final CuratedSources sources;
    private final JdbcClient jdbc;
    public UniversityProgramAdapter(CuratedSources sources,JdbcClient jdbc){this.sources=sources;this.jdbc=jdbc;}
    @Override public String name(){return NAME;}
    @Override public String version(){return VERSION;}
    @Override public List<ExtractedFact> extract(CanonicalPage page){throw new IllegalStateException("University extraction requires program context");}

    public List<ExtractedFact> extractHtml(String html,String finalUrl,Long programId) {
        if(programId==null) throw new IllegalArgumentException("University source needs one program");
        String oid=jdbc.sql("select value from program_external_id where program_id=:p and scheme='STUDYINFO_KOULUTUS_OID'")
                .param("p",programId).query(String.class).single();
        var candidate=sources.byEducationOid(oid);
        if(candidate==null) throw new IllegalArgumentException("No reviewed university association for "+oid);
        return extract(html,finalUrl,candidate);
    }

    /** Throws IllegalArgumentException when the page no longer is the reviewed program (identity changed: review). */
    public static List<ExtractedFact> extract(String html,String finalUrl,CuratedSources.Candidate candidate) {
        Profile profile=PROFILES.get(candidate.university());
        if(profile==null)throw new IllegalArgumentException("No page profile for "+candidate.university());
        if(!isProgramPage(finalUrl,candidate.university()))
            throw new IllegalArgumentException("Program redirect leaves the university program path");
        Document doc=Jsoup.parse(html,finalUrl);
        doc.select("script,style,noscript,svg,nav,header,footer,iframe,form").remove();
        if(doc.select("h1").size()!=1 || !normalize(doc.select("h1").text()).equals(normalize(candidate.heading())))
            throw new IllegalArgumentException("University program identity changed; review required");
        Map<String,String> values=new LinkedHashMap<>();
        for(var e:profile.labels().entrySet()) {
            String v=null;
            for(String label:e.getValue()) if(v==null) v=label(doc,profile,label);
            if(v!=null) values.put(e.getKey(),v);
        }
        String qualification=values.get("qualification");
        if(profile.labels().containsKey("qualification")) {
            if(qualification==null)throw new IllegalArgumentException("Qualification line missing; review required");
            String q=qualification.toLowerCase(Locale.ROOT);
            boolean bachelorEntry=candidate.entryLevel().equals("BACHELOR");
            if(!q.contains(bachelorEntry?"bachelor":"master")
                    || profile.statesPathway() && candidate.combined() && !q.contains("master"))
                throw new IllegalArgumentException("University qualification does not match the entry pathway");
        }
        String language=values.remove("language");
        if(profile.labels().containsKey("language")
                && (language==null || !language.toLowerCase(Locale.ROOT).contains("english")))
            throw new IllegalArgumentException("English instruction not confirmed by the page");
        if(candidate.university().equals("university-of-jyvaskyla")) feeHeading(doc).ifPresent(f->values.put("tuition_scope",f));
        if(candidate.key().equals("oulu-business-analytics") && values.containsKey("tuition_scope"))
            values.put("tuition_scope",optionFee(values.get("tuition_scope"),"Business Analytics"));
        values.values().removeIf(v->v==null || v.length()>MAX_VALUE);
        values.put("official_program_url",finalUrl);
        for(Element a:doc.select("a[href]")) {
            String text=a.text().toLowerCase(Locale.ROOT),url=a.absUrl("href");
            if(!CuratedSources.owned(url,candidate.university())) continue;
            if(text.contains("tuition fee") && (text.contains("scholarship") || url.contains("tuition"))) values.putIfAbsent("financial_url",url);
            if(text.contains("courses and curriculum") || text.contains("study guide for")) values.putIfAbsent("curriculum_url",url);
        }
        List<ExtractedFact> out=new ArrayList<>();
        for(var e:values.entrySet()) {
            String scope="application_period".equals(e.getKey())?e.getValue():"";
            Map<String,String> v=new LinkedHashMap<>(Map.of("field",e.getKey(),"value",e.getValue(),"educationOid",candidate.educationOid(),"scope",scope));
            LocalDate end="application_period".equals(e.getKey())?periodEnd(e.getValue()):null;
            if(end!=null)v.put("cycle",String.valueOf(end.getYear()));
            if("tuition_scope".equals(e.getKey()) && statesNonEuEea(e.getValue(),doc,profile)) v.put("applicantCategory","NON_EU_EEA");
            out.add(ExtractedFact.of(ExtractedFact.FactType.UNIVERSITY_FACT,e.getKey()+"|"+scope,v));
        }
        return out;
    }

    static boolean isProgramPage(String url,String university) {
        Profile profile=PROFILES.get(university);
        if(profile==null || !CuratedSources.owned(url,university)) return false;
        String path=java.net.URI.create(url).getPath();
        return profile.programPaths().stream().anyMatch(path::startsWith);
    }

    static String label(Document doc,Profile profile,String name) {
        List<String> found=new ArrayList<>();
        for(Element container:doc.select(profile.container())) {
            Element label=null;
            for(Element l:container.select(profile.label())) if(normalize(l.text()).equalsIgnoreCase(normalize(name))) label=l;
            if(label==null) continue;
            Element copy=container.clone();
            for(Element l:copy.select(profile.label())) if(normalize(l.text()).equalsIgnoreCase(normalize(name))) l.remove();
            copy.select("a").unwrap();
            String text=normalize(copy.wholeText());
            if(!text.isBlank() && !found.contains(text)) found.add(text);
        }
        // Nested containers (div:has(> h2)) can match more than once: keep the innermost, i.e. shortest, value.
        return found.stream().min(java.util.Comparator.comparingInt(String::length)).orElse(null);
    }

    /** Jyväskylä: "Tuition fee €16,000" plus the first list item that names the paying audience. */
    static java.util.Optional<String> feeHeading(Document doc) {
        for(Element h:doc.select("h2,h3,h4")) {
            var m=FEE_HEADING.matcher(normalize(h.text()));
            if(!m.matches()) continue;
            Element next=h.nextElementSibling();
            String audience=next==null?"":next.select("li").stream().map(Element::text).filter(t->t.contains("non-EU/EEA")).findFirst().orElse("");
            return java.util.Optional.of(normalize(m.group(1)+(audience.isEmpty()?"":" — "+audience)));
        }
        return java.util.Optional.empty();
    }

    /** A shared page listing several options' fees: keep only the named option's fee, with the page's audience note. */
    static String optionFee(String value,String option) {
        var m=Pattern.compile(Pattern.quote(option)+":\\s*([^,]+?€)").matcher(value);
        if(!m.find()) return null;
        var audience=Pattern.compile("\\((for [^)]*non-EU/EEA[^)]*)\\)").matcher(value);
        return option+": "+m.group(1).strip()+(value.contains("per year")?" per year":"")+(audience.find()?" ("+audience.group(1)+")":"");
    }

    static boolean statesNonEuEea(String value,Document doc,Profile profile) {
        if(value.contains("non-EU/EEA")) return true;
        // The label itself may carry the audience (Helsinki, Tampere, LUT).
        return profile.labels().getOrDefault("tuition_scope",List.of()).stream().anyMatch(l->l.contains("non-EU/EEA"));
    }

    static String normalize(String s) {
        return s==null?"":s.replace(' ',' ').replace('’','\'').replaceAll("\\s+"," ").strip();
    }

    public static LocalDate periodEnd(String value) {
        var numeric=Pattern.compile("(\\d{2}\\.\\d{2}\\.\\d{4})").matcher(value);
        LocalDate last=null;
        while(numeric.find())try{last=LocalDate.parse(numeric.group(),DateTimeFormatter.ofPattern("dd.MM.uuuu"));}catch(Exception ignored){}
        if(last!=null)return last;
        var english=Pattern.compile("(\\d{1,2} [A-Za-z]{3,9} \\d{4})").matcher(value);
        while(english.find()) {
            for(String f:List.of("d MMM uuuu","d MMMM uuuu"))
                try{last=LocalDate.parse(english.group(),DateTimeFormatter.ofPattern(f,Locale.ENGLISH));break;}catch(Exception ignored){}
        }
        return last;
    }
}
