# Draft: request to the Finnish National Agency for Education (Opetushallitus)

**Not sent.** You send it, from your own address, to the contact the API documentation names: `palaute@opintopolku.fi`
(source: the OpenAPI description served by `konfo-backend`, "Jos sinulla on kysyttävää rajapinnan käytöstä, lähetä sähköpostia
osoitteeseen palaute@opintopolku.fi"). Keep the reply; record it in `docs/data/official-api-connectors.md`.

Fill in the bracketed parts. Do not add credentials or anything about your private systems.

## English

Subject: Question about reuse terms of the Opintopolku external API (`/konfo-backend/external/*`)

Hello,

We are building [Pathway AI, a service that helps international applicants discover university degree programmes]. Your guidance page
for API users (opintopolku.fi/konfo/fi/sivu/opintopolku-fi-rajapinnat) says published education offerings can be fetched for display in
third-party web services through the open external API and that the published offering data is open data. Before we launch publicly we
would like to confirm the exact terms. Could you please tell us:

1. Under which licence is the data returned by `/konfo-backend/external/*` published (for example CC BY 4.0), and how should we attribute it?
2. May we keep a stored copy of the fields we use (programme name, degree level, field of education, planned duration, teaching language,
   campus municipality, application round closing dates, the Studyinfo page link) in our own database, refreshed from your API about weekly,
   in order to display and search them? We do not store free-text descriptions.
3. Are there limits on request frequency or volume beyond the `Crawl-delay: 30` in robots.txt, which we follow? We read about 10 to 30
   records per university per week and re-read discovery lists weekly.
4. Is commercial use of the data covered?
5. Whom should we contact if you ask us to correct or remove data, and how quickly should we act?

We identify our client as `PathwayAIBot/0.1 (+[repository or site URL])`. Thank you for your help.

[Name, role, contact]

## Suomeksi

Aihe: Kysymys Opintopolun ulkoisen rajapinnan (`/konfo-backend/external/*`) uudelleenkäyttöehdoista

Hei,

kehitämme palvelua [Pathway AI, joka auttaa kansainvälisiä hakijoita löytämään yliopistojen tutkintoon johtavia koulutuksia].
Rajapintojen käyttäjille tarkoitetulla sivullanne (opintopolku.fi/konfo/fi/sivu/opintopolku-fi-rajapinnat) kerrotaan, että Opintopolussa
julkaistua koulutustarjontaa voi hakea esitettäväksi kolmannen osapuolen verkkopalveluun avoimen ulkoisen rajapinnan kautta ja että
julkaistu koulutustarjontadata on avointa dataa. Ennen julkista julkaisua haluamme varmistaa tarkat ehdot. Voitteko kertoa:

1. Millä lisenssillä rajapinnan (`/konfo-backend/external/*`) palauttama data on julkaistu (esim. CC BY 4.0) ja miten lähde tulee mainita?
2. Saammeko säilyttää käyttämiemme kenttien kopion (koulutuksen nimi, tutkinnon taso, koulutusala, suunniteltu kesto, opetuskieli,
   kampuskunta, hakujen päättymispäivät, Opintopolun sivulinkki) omassa tietokannassamme ja päivittää sen rajapinnasta noin viikoittain
   esittämistä ja hakua varten? Emme tallenna vapaamuotoisia kuvaustekstejä.
3. Onko pyyntömäärille rajoituksia robots.txt:n `Crawl-delay: 30` -ohjeen lisäksi? Noudatamme sitä. Luemme noin 10–30 tietuetta
   yliopistoa kohden viikossa ja haemme hakulistaukset viikoittain.
4. Kattaako lupa kaupallisen käytön?
5. Keneen otamme yhteyttä, jos pyydätte korjaamaan tai poistamaan tietoja, ja kuinka nopeasti meidän tulee toimia?

Tunnistaudumme asiakkaana nimellä `PathwayAIBot/0.1 (+[repositorion tai sivuston osoite])`. Kiitos avusta.

[Nimi, rooli, yhteystiedot]
