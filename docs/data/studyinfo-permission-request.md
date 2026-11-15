# Draft: request to the Finnish National Agency for Education (Opetushallitus)

**Not sent (nothing has been sent from any session; verified 2026-11-15).** Fill in your own name, role and contact and send it from your own
address to the contact the API documentation names: `palaute@opintopolku.fi` (source: the OpenAPI description served by `konfo-backend`,
"Jos sinulla on kysyttävää rajapinnan käytöstä, lähetä sähköpostia osoitteeseen palaute@opintopolku.fi"). Keep the reply and record it in
`docs/data/official-api-connectors.md`. Do not add credentials or anything about private systems. Until a written answer exists, program
details stay behind the catalogue gate (`docs/data/public-catalogue-reuse.md`).

Sources to cite (both read on 2026-11-15):

- Konfo external API documentation: <https://wiki.eduuni.fi/spaces/ophPPK/pages/190612447/Koulutustarjonta+ja+Opintopolku.fi+tietomallit+ja+rajapinnat> (section "Opintopolku.fi:n (konfo) ulkoiset rajapinnat"). It says the external endpoints need no authentication, only a caller-id header, and suit showing published offerings on one's own sites. It states **no licence, attribution rule, terms, image rights or rate limits**; that gap is the reason for this request.
- The API user guidance page: opintopolku.fi/konfo/fi/sivu/opintopolku-fi-rajapinnat.

## English

Subject: Reuse terms for program data from the Opintopolku external API (`/konfo-backend/external/*`)

Hello,

We run Pathway AI [a service that helps international applicants discover university degree programmes]. The Konfo external API
documentation on wiki.eduuni.fi says the open external endpoints can be used to show published education offerings on your own websites,
but it states no licence, attribution rule or terms of use. Before we show program details publicly we would like written confirmation.

What we show today without an account is only institution identity (name, city, country, the institution's own website link, and how
many programmes we track). Programme-level details are not public.

For programme-level data we would like to publish, per programme: name, degree level, field of education, planned duration, teaching
language(s), campus municipality, tuition fee where the API states it, application period dates, admission requirements as published, and
the link to the programme's Studyinfo page. Could you please tell us:

1. Under which licence is this data published (for example CC BY 4.0), and how must we attribute it?
2. May we keep a stored copy of these fields in our own database, refreshed from your API about weekly, to display and search them?
3. Are there limits on request frequency or volume beyond the `Crawl-delay: 30` in robots.txt, which we follow?
4. Is commercial use covered?
5. Are there fields (for example tuition, requirements or free-text descriptions) that we may not reuse?
6. Whom should we contact if you ask us to correct or remove data, and how quickly should we act?

We identify our client as `PathwayAIBot/0.1 (+https://github.com/YunisSadig/pathway-ai-backend)`. Thank you for your help.

[Name, role, contact]

## Suomeksi

Aihe: Opintopolun ulkoisen rajapinnan (`/konfo-backend/external/*`) koulutustietojen uudelleenkäyttöehdot

Hei,

ylläpidämme palvelua Pathway AI [joka auttaa kansainvälisiä hakijoita löytämään yliopistojen tutkintoon johtavia koulutuksia]. Konfon
ulkoisen rajapinnan dokumentaatio (wiki.eduuni.fi) kertoo, että avoimilla ulkoisilla rajapinnoilla voi hakea julkaistua koulutustarjontaa
näkyviin omille verkkosivuille, mutta siinä ei mainita lisenssiä, lähteen mainintaa eikä käyttöehtoja. Ennen kuin näytämme koulutusten
tietoja julkisesti haluamme kirjallisen vahvistuksen.

Ilman tiliä näytämme nyt vain oppilaitoksen perustiedot (nimi, kaupunki, maa, oppilaitoksen oma verkkosivu ja seuraamiemme koulutusten
lukumäärä). Koulutuskohtaiset tiedot eivät ole julkisia.

Koulutuskohtaisesti haluaisimme julkaista: nimi, tutkinnon taso, koulutusala, suunniteltu kesto, opetuskieli(et), kampuskunta,
lukukausimaksu jos rajapinta sen kertoo, hakuajat, julkaistut pääsyvaatimukset ja linkki koulutuksen Opintopolku-sivulle. Voitteko kertoa:

1. Millä lisenssillä data on julkaistu (esim. CC BY 4.0) ja miten lähde tulee mainita?
2. Saammeko säilyttää näiden kenttien kopion omassa tietokannassamme ja päivittää sen rajapinnasta noin viikoittain esittämistä ja hakua varten?
3. Onko pyyntömäärille rajoituksia robots.txt:n `Crawl-delay: 30` -ohjeen lisäksi? Noudatamme sitä.
4. Kattaako lupa kaupallisen käytön?
5. Onko kenttiä (esim. lukukausimaksu, vaatimukset tai vapaamuotoiset kuvaukset), joita emme saa käyttää uudelleen?
6. Keneen otamme yhteyttä, jos pyydätte korjaamaan tai poistamaan tietoja, ja kuinka nopeasti meidän tulee toimia?

Tunnistaudumme asiakkaana nimellä `PathwayAIBot/0.1 (+https://github.com/YunisSadig/pathway-ai-backend)`. Kiitos avusta.

[Nimi, rooli, yhteystiedot]
