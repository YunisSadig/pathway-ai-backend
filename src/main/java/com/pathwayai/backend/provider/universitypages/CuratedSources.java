package com.pathwayai.backend.provider.universitypages;

import java.io.InputStream;
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** Connector associations and selection only. Program facts must still be read from their official sources. */
@Component
public class CuratedSources {
    /** Exactly this many offerings at each entry level form the curated Finnish catalogue. */
    public static final int PER_LEVEL = 10;

    /**
     * {@code reviewedTitleCodes}: only for a combined pathway whose Studyinfo level codes omit the bachelor's level; the
     * degree-title codes a reviewer confirmed (see {@code StudyinfoParser.parse(..., reviewedTitleCodes)}). Null otherwise.
     */
    public record Candidate(String key,String university,String educationOid,String implementationOid,String providerOid,
                            String entryLevel,boolean combined,String programUrl,String heading,String admissionsUrl,
                            String implementationProviderOid,List<String> reviewedTitleCodes,String studyOption) {
        public List<String> titleCodes(){return reviewedTitleCodes==null?List.of():reviewedTitleCodes;}
    }

    /** The one official web host and publisher name per connector-backed university. Code, not configuration. */
    public record Owner(String host,String publisher) { }
    static final Map<String,Owner> OWNERS=Map.of(
            "aalto-university",new Owner("www.aalto.fi","Aalto University"),
            "university-of-helsinki",new Owner("www.helsinki.fi","University of Helsinki"),
            "tampere-university",new Owner("www.tuni.fi","Tampere University"),
            "lut-university",new Owner("www.lut.fi","LUT University"),
            "university-of-oulu",new Owner("www.oulu.fi","University of Oulu"),
            "university-of-jyvaskyla",new Owner("www.jyu.fi","University of Jyväskylä"),
            "university-of-turku",new Owner("www.utu.fi","University of Turku"));

    private final List<Candidate> candidates;
    @Autowired
    public CuratedSources(ResourceLoader resources,@Value("${app.curated.sources:classpath:reference-data/curated-finland.json}") String location) {
        try(InputStream in=resources.getResource(location).getInputStream()) {
            candidates=List.of(JsonMapper.builder().build().readValue(in,Candidate[].class));
        } catch(java.io.IOException e) {throw new java.io.UncheckedIOException(e);}
        if(candidates.size()!=2*PER_LEVEL || candidates.stream().filter(c->c.entryLevel().equals("BACHELOR")).count()!=PER_LEVEL
                || candidates.stream().filter(c->c.entryLevel().equals("MASTER")).count()!=PER_LEVEL
                || candidates.stream().map(Candidate::educationOid).distinct().count()!=2*PER_LEVEL
                || candidates.stream().map(Candidate::implementationOid).distinct().count()!=2*PER_LEVEL
                || candidates.stream().map(Candidate::key).distinct().count()!=2*PER_LEVEL)
            throw new IllegalArgumentException("Curated selection requires "+2*PER_LEVEL+" distinct offerings, "+PER_LEVEL
                    +" bachelor-entry and "+PER_LEVEL+" master");
        for(var c:candidates) {
            if(!owned(c.programUrl(),c.university()) || c.admissionsUrl()!=null && !owned(c.admissionsUrl(),c.university()))
                throw new IllegalArgumentException("Curated source is not university-owned: "+c.key());
            if(!c.titleCodes().isEmpty() && (!c.combined() || !c.entryLevel().equals("BACHELOR")))
                throw new IllegalArgumentException("Reviewed degree titles only apply to a bachelor-entry combined pathway: "+c.key());
        }
    }

    public List<Candidate> candidates(){return candidates;}
    public Candidate byEducationOid(String oid){return candidates.stream().filter(c->c.educationOid().equals(oid)).findFirst().orElse(null);}
    public static String publisher(String university){
        Owner o=OWNERS.get(university);
        if(o==null)throw new IllegalArgumentException("Not a connector-backed university: "+university);
        return o.publisher();
    }

    public static void validateStudyinfo(Candidate candidate,String json) {
        var root=JsonMapper.builder().build().readTree(json);
        if(!candidate.educationOid().equals(root.path("oid").asText()) || !"julkaistu".equals(root.path("tila").asText()))
            throw new IllegalArgumentException("Education identity is missing or not published");
        var selected=new java.util.ArrayList<tools.jackson.databind.JsonNode>();
        root.path("toteutukset").forEach(t->{if(candidate.implementationOid().equals(t.path("oid").asText()))selected.add(t);});
        if(selected.size()!=1 || !"julkaistu".equals(selected.getFirst().path("tila").asText()))
            throw new IllegalArgumentException("Reviewed implementation is absent or unpublished");
        var providers=selected.getFirst().path("tarjoajat");
        if(providers.size()!=1 || !candidate.implementationProviderOid().equals(providers.get(0).path("oid").asText()))
            throw new IllegalArgumentException("Implementation provider changed; reviewed institution association required");
    }

    /**
     * A highlighted study option must still be named by the provider, in the reviewed implementation's own published
     * text, in the same passage as the words "study track". Returns the option, or throws so the import goes to review.
     */
    public static String validateStudyOption(Candidate candidate,String json) {
        if(candidate.studyOption()==null)return null;
        var root=JsonMapper.builder().build().readTree(json);
        String option=candidate.studyOption().toLowerCase(java.util.Locale.ROOT);
        for(var t:root.path("toteutukset")) {
            if(!candidate.implementationOid().equals(t.path("oid").asText()))continue;
            for(var info:t.path("metadata").path("opetus").path("lisatiedot")) {
                String text=info.path("teksti").path("en").asText("").replaceAll("<[^>]+>"," ").replace("&nbsp;"," ")
                        .replaceAll("\\s+"," ").toLowerCase(java.util.Locale.ROOT);
                if(text.contains("study track") && text.contains(option))return candidate.studyOption();
            }
        }
        throw new IllegalArgumentException("Study option '"+candidate.studyOption()+"' is no longer stated for "+candidate.key()+"; review required");
    }

    /** University-owned by any connector-backed university (a fact's own program association is checked separately). */
    public static boolean ownedByAny(String url) {
        return OWNERS.keySet().stream().anyMatch(u->owned(url,u));
    }

    public static boolean owned(String url,String university) {
        if(url==null) return false;
        try {
            URI uri=URI.create(url);
            Owner owner=OWNERS.get(university);
            if(owner==null)return false;
            String host=owner.host();
            return "https".equals(uri.getScheme()) && uri.getHost()!=null
                    && (host.equals(uri.getHost()) || uri.getHost().endsWith("."+host.substring(4))) && uri.getUserInfo()==null
                    && uri.getPort()==-1 && uri.getPath()!=null && uri.getPath().startsWith("/en/") && uri.getPath().length()>8;
        } catch(IllegalArgumentException e){return false;}
    }
}
