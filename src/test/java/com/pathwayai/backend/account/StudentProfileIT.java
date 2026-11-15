package com.pathwayai.backend.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pathwayai.backend.TestcontainersConfiguration;
import jakarta.servlet.http.Cookie;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Random;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Student profile v2 against a real PostgreSQL: the conditional onboarding branches, grading scales, exams, budgets, destinations,
 * authorization between students, the picture upload and the preservation of pre-existing profile data. Synthetic data only.
 */
@SpringBootTest(properties = {"app.access.catalogue-public=false", "app.discovery.restrict-to-verified-fresh=false"})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class StudentProfileIT {

    static final String URL = "/api/v1/account/student-profile";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void seedCatalogue() {
        jdbc.update("insert into country (iso2_code, name) values ('ZG', 'Gateland')");
        long country = jdbc.queryForObject("select id from country where iso2_code = 'ZG'", Long.class);
        long uni = jdbc.queryForObject("""
                insert into university (country_id, slug, name, verification_status, verified_on)
                values (?, 'zz-profile-u', 'Synthetic Profile University', 'VERIFIED', current_date) returning id""", Long.class, country);
        jdbc.update("""
                insert into degree_program (university_id, slug, name, degree_level, verification_status, verified_on)
                values (?, 'p', 'Synthetic Profile Program', 'MASTER', 'VERIFIED', current_date)""", uni);
    }

    @AfterEach
    void clean() {
        jdbc.update("delete from user_account where email_normalized like '%@it.example'");
        jdbc.update("delete from auth_throttle");
        jdbc.update("delete from spring_session");
        jdbc.update("delete from university where slug = 'zz-profile-u'");
        jdbc.update("delete from country where iso2_code = 'ZG'");
    }

    // ------------------------------------------------------------------------------------------------ helpers

    record Student(String email, Cookie session) {
    }

    Student student() throws Exception {
        String email = "stu-" + UUID.randomUUID().toString().substring(0, 8) + "@it.example";
        Cookie c = mvc.perform(post("/api/v1/account/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + AccountApiIT.PASSWORD + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getCookie("PATHWAY_SESSION");
        return new Student(email, c);
    }

    void grant(Student s) {
        jdbc.update("update user_account set catalogue_access = true where email_normalized = ?", s.email());
    }

    ResultActions save(Student s, String json) throws Exception {
        return mvc.perform(put(URL).cookie(s.session()).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    ResultActions complete(Student s) throws Exception {
        return mvc.perform(post(URL + "/complete").cookie(s.session()));
    }

    static String doc(String educationPart, String rest) {
        return "{\"citizenship\":\"AZ\",\"residence\":\"AZ\"," + educationPart
                + ",\"studyArea\":\"CS_IT\",\"specialization\":\"software_engineering\",\"targetDegreeLevel\":\"MASTER\","
                + "\"internshipsStatus\":\"NONE\",\"volunteeringStatus\":\"NONE\",\"examsStatus\":\"NOT_TAKEN\","
                + "\"budget\":{\"status\":\"AMOUNT\",\"amount\":5000,\"currency\":\"AZN\"}" + (rest.isEmpty() ? "" : "," + rest) + "}";
    }

    static final String HIGH_SCHOOL = "\"educationStatus\":\"HIGH_SCHOOL\",\"school\":{\"institution\":\"Synthetic School 7\",\"country\":\"AZ\","
            + "\"currentYear\":11,\"graduationYear\":2027,\"grade\":{\"status\":\"ENTERED\",\"scale\":\"FIVE_POINT\",\"value\":4.5}}";

    static String bachelor(String grade) {
        return "\"educationStatus\":\"BACHELOR\",\"bachelor\":{\"institution\":\"Synthetic University A\",\"country\":\"KZ\","
                + "\"program\":\"Synthetic Computing\",\"currentYear\":3,\"graduationYear\":2028,\"grade\":" + grade + "}";
    }

    // ------------------------------------------------------------------------------------------------ options

    @Test
    void optionsListTheFiveSupportedCountriesCurrenciesAndOnlyDefinedScales() throws Exception {
        Student s = student();
        mvc.perform(get("/api/v1/account/options").cookie(s.session())).andExpect(status().isOk())
                .andExpect(jsonPath("$.countries", hasSize(5)))
                .andExpect(jsonPath("$.countries[*].code", hasItem("KG")))
                .andExpect(jsonPath("$.currencies", hasItem("USD")))
                .andExpect(jsonPath("$.currencies", hasItem("AZN")))
                .andExpect(jsonPath("$.currencies", hasSize(13)))
                .andExpect(jsonPath("$.scales[*].scale", hasItem("PERCENT_100")))
                .andExpect(jsonPath("$.toeflNewScaleEffectiveFrom").value("2026-01-21"));
        mvc.perform(get("/api/v1/account/options")).andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------------------------------------------ onboarding branches

    @Test
    void anAzerbaijaniHighSchoolStudentEntersSchoolMarksAndCompletes() throws Exception {
        Student s = student();
        save(s, doc(HIGH_SCHOOL, "\"skills\":[{\"code\":\"python\"},{\"label\":\"Robotics club\"}]")).andExpect(status().isOk())
                .andExpect(jsonPath("$.profile.school.grade.scale").value("FIVE_POINT"))
                .andExpect(jsonPath("$.profile.school.grade.value").value(4.5))
                .andExpect(jsonPath("$.profile.bachelor").doesNotExist())
                .andExpect(jsonPath("$.profile.skills", hasSize(2)))
                .andExpect(jsonPath("$.completeness.complete").value(true)); // no catalogue access: no destination is required
        complete(s).andExpect(status().isOk()).andExpect(jsonPath("$.profile.onboarding.status").value("COMPLETED"));
    }

    @Test
    void aKazakhstaniBachelorStudentEntersAGpaWithIeltsAndInternships() throws Exception {
        Student s = student();
        String body = doc(bachelor("{\"status\":\"ENTERED\",\"scale\":\"GPA_4\",\"value\":3.45}"),
                "\"skills\":[{\"code\":\"java\"}],\"internshipsStatus\":\"HAS\",\"internships\":["
                        + "{\"organization\":\"Synthetic Soft LLC\",\"role\":\"Intern\",\"startMonth\":\"2025-06\",\"endMonth\":\"2025-08\",\"ongoing\":false},"
                        + "{\"organization\":\"Synthetic Bank\",\"role\":\"Analyst intern\",\"startMonth\":\"2026-06\",\"ongoing\":true}],"
                        + "\"examsStatus\":\"TAKEN\",\"exams\":[{\"exam\":\"IELTS\",\"variant\":\"ACADEMIC\",\"testDate\":\"2026-03-01\","
                        + "\"overall\":6.5,\"sections\":[{\"section\":\"LISTENING\",\"score\":7},{\"section\":\"READING\",\"score\":6.5}]}]")
                .replace("\"internshipsStatus\":\"NONE\",", "").replace("\"examsStatus\":\"NOT_TAKEN\",", "");
        save(s, body).andExpect(status().isOk()).andExpect(jsonPath("$.profile.internships", hasSize(2)))
                .andExpect(jsonPath("$.profile.exams[0].sections", hasSize(2))).andExpect(jsonPath("$.completeness.complete").value(true));
        complete(s).andExpect(status().isOk());
    }

    @Test
    void aGeorgianEconomicsStudentUsesLetterGrades() throws Exception {
        Student s = student();
        String body = doc(bachelor("{\"status\":\"ENTERED\",\"scale\":\"LETTER\",\"letter\":\"B+\"}"), "\"skills\":[{\"code\":\"excel\"}]")
                .replace("CS_IT", "ECON_BUSINESS").replace("software_engineering", "finance").replace("\"citizenship\":\"AZ\",\"residence\":\"AZ\"",
                        "\"citizenship\":\"GE\",\"residence\":\"GE\"");
        save(s, body).andExpect(status().isOk()).andExpect(jsonPath("$.profile.bachelor.grade.letter").value("B+"))
                .andExpect(jsonPath("$.profile.specialization").value("finance"));
        // a letter grade has no number, and a number on a letter scale is refused
        save(s, body.replace("\"letter\":\"B+\"", "\"letter\":\"B+\",\"value\":3")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.violations[0].code").value("CONFLICT"));
        // a specialization must belong to the chosen area
        save(s, body.replace("finance", "cybersecurity")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.violations[0].field").value("specialization"));
    }

    @Test
    void anUzbekistaniApplicantWithAnotherScaleStatesTheScaleAndIsRangeChecked() throws Exception {
        Student s = student();
        String other = "{\"status\":\"ENTERED\",\"scale\":\"OTHER\",\"scaleLabel\":\"Out of 20\",\"scaleMin\":0,\"scaleMax\":20,\"value\":15.5}";
        save(s, doc(bachelor(other), "")).andExpect(status().isOk()).andExpect(jsonPath("$.profile.bachelor.grade.scaleLabel").value("Out of 20"))
                .andExpect(jsonPath("$.profile.bachelor.grade.scaleMax").value(20));
        save(s, doc(bachelor(other.replace("15.5", "21")), "")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.violations[0].code").value("OUT_OF_RANGE"));
        save(s, doc(bachelor(other.replace("\"scaleMax\":20", "\"scaleMax\":0")), "")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.violations[0].code").value("INVALID_SCALE"));
        save(s, doc(bachelor(other.replace("\"scaleLabel\":\"Out of 20\",", "")), "")).andExpect(status().isBadRequest());
    }

    @Test
    void gradeScalesAreRangeCheckedAndNeverConverted() throws Exception {
        Student s = student();
        for (String bad : new String[] {"{\"status\":\"ENTERED\",\"scale\":\"FIVE_POINT\",\"value\":5.5}",
                "{\"status\":\"ENTERED\",\"scale\":\"FIVE_POINT\",\"value\":0}",
                "{\"status\":\"ENTERED\",\"scale\":\"GPA_4\",\"value\":4.3}",
                "{\"status\":\"ENTERED\",\"scale\":\"PERCENT_100\",\"value\":101}",
                "{\"status\":\"ENTERED\",\"scale\":\"TEN_POINT\",\"value\":3.333}",
                "{\"status\":\"ENTERED\",\"scale\":\"LETTER\",\"letter\":\"Z\"}",
                "{\"status\":\"ENTERED\"}",
                "{\"status\":\"NOT_AVAILABLE_YET\",\"scale\":\"GPA_4\",\"value\":3}"}) {
            save(s, doc(bachelor(bad), "")).andExpect(status().isBadRequest());
        }
        // a value is stored exactly as entered: 3.45 out of 4.0 stays 3.45 on the GPA scale, with no US-GPA or percentage derived from it
        save(s, doc(bachelor("{\"status\":\"ENTERED\",\"scale\":\"PERCENT_100\",\"value\":78}"), "")).andExpect(status().isOk())
                .andExpect(jsonPath("$.profile.bachelor.grade.scale").value("PERCENT_100")).andExpect(jsonPath("$.profile.bachelor.grade.value").value(78));
        // "no grade yet" is an honest answer and counts as complete for that field
        save(s, doc(bachelor("{\"status\":\"NOT_AVAILABLE_YET\"}"), "")).andExpect(status().isOk())
                .andExpect(jsonPath("$.completeness.complete").value(true));
    }

    @Test
    void aKyrgyzMasterStudentMustAlsoDescribeTheBachelorQualification() throws Exception {
        Student s = student();
        String master = "\"educationStatus\":\"MASTER\",\"master\":{\"institution\":\"Synthetic University M\",\"country\":\"KG\","
                + "\"program\":\"Synthetic Economics\",\"currentYear\":1,\"graduationYear\":2027,\"grade\":{\"status\":\"NOT_AVAILABLE_YET\"}}";
        save(s, doc(master, "")).andExpect(status().isOk()).andExpect(jsonPath("$.completeness.complete").value(false))
                .andExpect(jsonPath("$.completeness.missing", hasItem("bachelor.institution")));
        complete(s).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INCOMPLETE"));
        String withBachelor = master + ",\"bachelor\":{\"institution\":\"Synthetic University B\",\"country\":\"KG\",\"program\":\"Synthetic Economics\","
                + "\"graduationYear\":2025,\"grade\":{\"status\":\"ENTERED\",\"scale\":\"PERCENT_100\",\"value\":84}}";
        save(s, doc(withBachelor, "")).andExpect(status().isOk()).andExpect(jsonPath("$.completeness.complete").value(true));
        complete(s).andExpect(status().isOk());
    }

    @Test
    void aGraduatedStudentDescribesTheHighestCompletedQualification() throws Exception {
        Student s = student();
        String graduated = "\"educationStatus\":\"GRADUATED\",\"completedQualification\":\"BACHELOR\",\"bachelor\":{\"institution\":\"Synthetic University G\","
                + "\"country\":\"UZ\",\"program\":\"Synthetic IT\",\"graduationYear\":2024,\"grade\":{\"status\":\"ENTERED\",\"scale\":\"FIVE_POINT\",\"value\":4.2}}";
        save(s, doc(graduated, "")).andExpect(status().isOk()).andExpect(jsonPath("$.completeness.complete").value(true));
        save(s, doc(graduated.replace("\"completedQualification\":\"BACHELOR\",", ""), "")).andExpect(status().isOk())
                .andExpect(jsonPath("$.completeness.missing", hasItem("completedQualification")));
    }

    @Test
    void experienceAnswersAreHonestAndNeverForcedIntoInventedEntries() throws Exception {
        Student s = student();
        // "No internships yet" needs no entries; saying "yes" without an entry is incomplete; entries contradicting "none" are refused
        save(s, doc(HIGH_SCHOOL, "")).andExpect(status().isOk()).andExpect(jsonPath("$.completeness.complete").value(true));
        save(s, doc(HIGH_SCHOOL, "").replace("\"internshipsStatus\":\"NONE\"", "\"internshipsStatus\":\"HAS\"")).andExpect(status().isOk())
                .andExpect(jsonPath("$.completeness.missing", hasItem("internships")));
        save(s, doc(HIGH_SCHOOL, "\"internships\":[{\"organization\":\"X\",\"role\":\"Y\"}]")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.violations[0].code").value("CONFLICT"));
        save(s, doc(HIGH_SCHOOL, "").replace("\"volunteeringStatus\":\"NONE\"", "\"volunteeringStatus\":\"HAS\",\"volunteering\":["
                + "{\"organization\":\"Synthetic Charity\",\"role\":\"Helper\",\"startMonth\":\"2026-02\",\"endMonth\":\"2026-01\"}]"))
                .andExpect(status().isBadRequest()); // the end is before the start
    }

    // ------------------------------------------------------------------------------------------------ exams

    String withExam(String exam) {
        return doc(HIGH_SCHOOL, "\"exams\":[" + exam + "]").replace("\"examsStatus\":\"NOT_TAKEN\"", "\"examsStatus\":\"TAKEN\"");
    }

    @Test
    void examsAreValidatedAccordingToTheirOfficialRulesAndNeverConverted() throws Exception {
        Student s = student();
        // TOEFL: the legacy 0-120 report and the new 1-6 report are different things, stored as reported
        save(s, withExam("{\"exam\":\"TOEFL\",\"variant\":\"LEGACY_0_120\",\"testDate\":\"2025-09-10\",\"overall\":100}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.profile.exams[0].variant").value("LEGACY_0_120")).andExpect(jsonPath("$.profile.exams[0].overall").value(100));
        save(s, withExam("{\"exam\":\"TOEFL\",\"variant\":\"NEW_1_6\",\"testDate\":\"2026-03-01\",\"overall\":5.5}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.profile.exams[0].variant").value("NEW_1_6"));
        // the 1-6 report cannot exist for a test taken before 21 January 2026, and half points only
        save(s, withExam("{\"exam\":\"TOEFL\",\"variant\":\"NEW_1_6\",\"testDate\":\"2025-12-01\",\"overall\":5.5}")).andExpect(status().isBadRequest());
        save(s, withExam("{\"exam\":\"TOEFL\",\"variant\":\"NEW_1_6\",\"testDate\":\"2026-03-01\",\"overall\":5.25}")).andExpect(status().isBadRequest());
        save(s, withExam("{\"exam\":\"TOEFL\",\"variant\":\"NEW_1_6\",\"testDate\":\"2026-03-01\",\"overall\":7}")).andExpect(status().isBadRequest());
        save(s, withExam("{\"exam\":\"TOEFL\",\"variant\":\"LEGACY_0_120\",\"testDate\":\"2025-09-10\",\"overall\":121}")).andExpect(status().isBadRequest());
        save(s, withExam("{\"exam\":\"TOEFL\",\"testDate\":\"2026-03-01\",\"overall\":5}")).andExpect(status().isBadRequest()); // report type missing
        // IELTS: bands 1-9 in half bands
        save(s, withExam("{\"exam\":\"IELTS\",\"variant\":\"GENERAL\",\"testDate\":\"2026-03-01\",\"overall\":7.5}")).andExpect(status().isOk());
        save(s, withExam("{\"exam\":\"IELTS\",\"variant\":\"ACADEMIC\",\"testDate\":\"2026-03-01\",\"overall\":6.75}")).andExpect(status().isBadRequest());
        save(s, withExam("{\"exam\":\"IELTS\",\"variant\":\"ACADEMIC\",\"testDate\":\"2026-03-01\",\"overall\":9.5}")).andExpect(status().isBadRequest());
        // SAT: 400-1600, sections 200-800, no report variant
        save(s, withExam("{\"exam\":\"SAT\",\"testDate\":\"2026-03-01\",\"overall\":1340,\"sections\":[{\"section\":\"MATH\",\"score\":700},"
                + "{\"section\":\"READING_WRITING\",\"score\":640}]}")).andExpect(status().isOk());
        save(s, withExam("{\"exam\":\"SAT\",\"testDate\":\"2026-03-01\",\"overall\":1700}")).andExpect(status().isBadRequest());
        save(s, withExam("{\"exam\":\"SAT\",\"testDate\":\"2026-03-01\",\"overall\":1300,\"sections\":[{\"section\":\"LISTENING\",\"score\":600}]}"))
                .andExpect(status().isBadRequest());
        // a test in the future, a duplicate and "not taken" with results are refused
        save(s, withExam("{\"exam\":\"SAT\",\"testDate\":\"2090-03-01\",\"overall\":1300}")).andExpect(status().isBadRequest());
        String one = "{\"exam\":\"SAT\",\"testDate\":\"2026-03-01\",\"overall\":1300}";
        save(s, withExam(one + "," + one)).andExpect(status().isBadRequest());
        save(s, withExam(one).replace("\"examsStatus\":\"TAKEN\"", "\"examsStatus\":\"NOT_TAKEN\"")).andExpect(status().isBadRequest());
        // GRE, GMAT and Duolingo are not part of this release
        save(s, withExam("{\"exam\":\"GRE\",\"testDate\":\"2026-03-01\",\"overall\":320}")).andExpect(status().isBadRequest());
    }

    // ------------------------------------------------------------------------------------------------ budget

    @Test
    void budgetsKeepTheirCurrencyWithoutAnyConversionAndAllowNotSureYet() throws Exception {
        Student s = student();
        for (String cur : new String[] {"AZN", "EUR", "PLN", "USD", "KZT", "UZS"}) {
            save(s, doc(HIGH_SCHOOL, "").replace("\"currency\":\"AZN\"", "\"currency\":\"" + cur + "\"")).andExpect(status().isOk())
                    .andExpect(jsonPath("$.profile.budget.currency").value(cur)).andExpect(jsonPath("$.profile.budget.amount").value(5000));
        }
        save(s, doc(HIGH_SCHOOL, "").replace("\"amount\":5000", "\"amount\":260000000.50").replace("AZN", "UZS")).andExpect(status().isOk())
                .andExpect(jsonPath("$.profile.budget.amount").value(260000000.5));
        save(s, doc(HIGH_SCHOOL, "").replace("AZN", "JPY")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.violations[0].code").value("UNKNOWN_VALUE"));
        save(s, doc(HIGH_SCHOOL, "").replace("5000", "0")).andExpect(status().isBadRequest());
        save(s, doc(HIGH_SCHOOL, "").replace("5000", "100.123")).andExpect(status().isBadRequest());
        String notSure = doc(HIGH_SCHOOL, "").replace("{\"status\":\"AMOUNT\",\"amount\":5000,\"currency\":\"AZN\"}", "{\"status\":\"NOT_SURE\"}");
        save(s, notSure).andExpect(status().isOk()).andExpect(jsonPath("$.profile.budget.status").value("NOT_SURE"))
                .andExpect(jsonPath("$.profile.budget.amount").doesNotExist()).andExpect(jsonPath("$.completeness.complete").value(true));
        save(s, notSure.replace("{\"status\":\"NOT_SURE\"}", "{\"status\":\"NOT_SURE\",\"amount\":100}")).andExpect(status().isBadRequest());
    }

    // ------------------------------------------------------------------------------------------------ countries

    @Test
    void unsupportedCountriesAreRefusedWithAClearCodeInsteadOfBeingForcedIntoAnotherAnswer() throws Exception {
        Student s = student();
        save(s, doc(HIGH_SCHOOL, "").replace("\"citizenship\":\"AZ\"", "\"citizenship\":\"US\"")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.violations[0].field").value("citizenship")).andExpect(jsonPath("$.violations[0].code").value("UNSUPPORTED_COUNTRY"));
        save(s, doc(HIGH_SCHOOL, "").replace("\"residence\":\"AZ\"", "\"residence\":\"DE\"")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.violations[0].field").value("residence"));
        // citizenship and residence are different: a Georgian citizen can live in Azerbaijan
        save(s, doc(HIGH_SCHOOL, "").replace("\"citizenship\":\"AZ\"", "\"citizenship\":\"GE\"")).andExpect(status().isOk())
                .andExpect(jsonPath("$.profile.citizenship").value("GE")).andExpect(jsonPath("$.profile.residence").value("AZ"));
    }

    // ------------------------------------------------------------------------------------------------ destinations and the catalogue gate

    @Test
    void anAccountWithoutCatalogueAccessReceivesNothingFromTheCatalogueAndCanStillFinish() throws Exception {
        Student s = student();
        mvc.perform(get("/api/v1/account/options/destinations").cookie(s.session())).andExpect(status().isOk())
                .andExpect(jsonPath("$.restricted").value(true)).andExpect(jsonPath("$.countries", hasSize(0)))
                .andExpect(jsonPath("$.universities", hasSize(0)));
        save(s, doc(HIGH_SCHOOL, "\"destinations\":[\"ZG\"]")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.violations[0].code").value("CATALOGUE_PROTECTED"));
        save(s, doc(HIGH_SCHOOL, "")).andExpect(status().isOk());
        complete(s).andExpect(status().isOk());
    }

    @Test
    void anAccountWithAccessSeesOnlyRealCatalogueDestinationsAndMustChooseOne() throws Exception {
        Student s = student();
        grant(s);
        mvc.perform(get("/api/v1/account/options/destinations").cookie(s.session())).andExpect(status().isOk())
                .andExpect(jsonPath("$.restricted").value(false)).andExpect(jsonPath("$.countries", hasSize(1)))
                .andExpect(jsonPath("$.countries[0].code").value("ZG")).andExpect(jsonPath("$.universities[0].slug").value("zz-profile-u"));
        save(s, doc(HIGH_SCHOOL, "")).andExpect(status().isOk()).andExpect(jsonPath("$.completeness.missing", hasItem("destinations")));
        complete(s).andExpect(status().isBadRequest());
        // the United States, Japan and the rest are not selectable: no program record exists there
        for (String unavailable : new String[] {"US", "JP", "CA", "CN", "KR"}) {
            save(s, doc(HIGH_SCHOOL, "\"destinations\":[\"" + unavailable + "\"]")).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.violations[0].code").value("DESTINATION_UNAVAILABLE"));
        }
        save(s, doc(HIGH_SCHOOL, "\"destinations\":[\"ZG\"],\"preferredUniversities\":[\"zz-profile-u\"]")).andExpect(status().isOk())
                .andExpect(jsonPath("$.completeness.complete").value(true)).andExpect(jsonPath("$.profile.preferredUniversities[0]").value("zz-profile-u"));
        save(s, doc(HIGH_SCHOOL, "\"destinations\":[\"ZG\"],\"preferredUniversities\":[\"not-a-listed-university\"]")).andExpect(status().isBadRequest());
        complete(s).andExpect(status().isOk());
        // revoking access hides the catalogue again; the stored choice stays but nothing new from the catalogue is shown
        jdbc.update("update user_account set catalogue_access = false where email_normalized = ?", s.email());
        mvc.perform(get("/api/v1/account/options/destinations").cookie(s.session())).andExpect(jsonPath("$.restricted").value(true));
    }

    // ------------------------------------------------------------------------------------------------ editing and preservation

    @Test
    void changingTheEducationStatusKeepsTheUnrelatedAnswers() throws Exception {
        Student s = student();
        String both = HIGH_SCHOOL + ",\"bachelor\":{\"institution\":\"Synthetic University A\",\"country\":\"KZ\",\"program\":\"Synthetic Computing\","
                + "\"currentYear\":1,\"graduationYear\":2030,\"grade\":{\"status\":\"NOT_AVAILABLE_YET\"}}";
        save(s, doc(both, "")).andExpect(status().isOk());
        // the student moves on to a bachelor's degree: the school answers are still there, the questions simply changed
        save(s, doc(both.replace("\"educationStatus\":\"HIGH_SCHOOL\"", "\"educationStatus\":\"BACHELOR\""), "")).andExpect(status().isOk())
                .andExpect(jsonPath("$.profile.educationStatus").value("BACHELOR")).andExpect(jsonPath("$.profile.school.institution").value("Synthetic School 7"))
                .andExpect(jsonPath("$.profile.bachelor.institution").value("Synthetic University A")).andExpect(jsonPath("$.completeness.complete").value(true));
    }

    @Test
    void aFinishedProfileCanBeEditedButNeverBecomesIncomplete() throws Exception {
        Student s = student();
        save(s, doc(HIGH_SCHOOL, "")).andExpect(status().isOk());
        complete(s).andExpect(status().isOk());
        save(s, doc(HIGH_SCHOOL, "").replace("\"residence\":\"AZ\",", "")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.violations[?(@.field=='residence')].code").value("REQUIRED"));
        save(s, doc(HIGH_SCHOOL, "").replace("Synthetic School 7", "Synthetic School 8")).andExpect(status().isOk())
                .andExpect(jsonPath("$.profile.onboarding.status").value("COMPLETED")).andExpect(jsonPath("$.profile.school.institution").value("Synthetic School 8"));
    }

    @Test
    void draftsAreSavedHalfFinishedAndResumed() throws Exception {
        Student s = student();
        save(s, "{\"citizenship\":\"KZ\",\"onboarding\":{\"step\":2}}").andExpect(status().isOk())
                .andExpect(jsonPath("$.profile.onboarding.status").value("IN_PROGRESS")).andExpect(jsonPath("$.profile.onboarding.step").value(2))
                .andExpect(jsonPath("$.completeness.complete").value(false));
        mvc.perform(get(URL).cookie(s.session())).andExpect(jsonPath("$.profile.citizenship").value("KZ"))
                .andExpect(jsonPath("$.profile.onboarding.step").value(2));
    }

    @Test
    void entriesFromBeforeGuidedOnboardingAreKeptUntouchedAndNeverReinterpreted() throws Exception {
        Student s = student();
        UUID id = jdbc.queryForObject("select id from user_account where email_normalized = ?", UUID.class, s.email());
        // a profile exactly as the earlier model stored it
        jdbc.update("insert into student_profile (account_id, current_country, current_university, gpa, gpa_scale_max, field_of_study) "
                + "values (?, 'AZ', 'Synthetic Old University', 3.4, 4.0, 'Synthetic field')", id);
        jdbc.update("insert into student_language_qualification (account_id, test, overall) values (?, 'IELTS', 6.5)", id);
        mvc.perform(get(URL).cookie(s.session())).andExpect(status().isOk()).andExpect(jsonPath("$.legacy.gpa").value(3.4))
                .andExpect(jsonPath("$.legacy.gpaScaleMax").value(4.0)).andExpect(jsonPath("$.legacy.languageQualifications[0].test").value("IELTS"))
                .andExpect(jsonPath("$.profile.residence").value("AZ")).andExpect(jsonPath("$.profile.bachelor").doesNotExist())
                .andExpect(jsonPath("$.profile.exams", hasSize(0)));
        save(s, doc(HIGH_SCHOOL, "")).andExpect(status().isOk());
        // the new model never copies or converts the old values, and saving does not touch them
        assertThat(jdbc.queryForObject("select gpa from student_profile where account_id = ?", java.math.BigDecimal.class, id)).isEqualByComparingTo("3.4");
        assertThat(jdbc.queryForObject("select count(*) from student_education where account_id = ?", Integer.class, id)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from student_language_qualification where account_id = ?", Integer.class, id)).isEqualTo(1);
    }

    // ------------------------------------------------------------------------------------------------ authorization

    @Test
    void oneStudentNeverSeesOrChangesAnother() throws Exception {
        Student a = student();
        Student b = student();
        save(a, doc(HIGH_SCHOOL, "\"skills\":[{\"label\":\"Private hobby of A\"}]")).andExpect(status().isOk());
        mvc.perform(get(URL).cookie(b.session())).andExpect(status().isOk()).andExpect(jsonPath("$.profile.citizenship").doesNotExist())
                .andExpect(jsonPath("$.profile.skills", hasSize(0)));
        save(b, "{\"citizenship\":\"KG\"}").andExpect(status().isOk());
        mvc.perform(get(URL).cookie(a.session())).andExpect(jsonPath("$.profile.citizenship").value("AZ"));
        mvc.perform(get(URL)).andExpect(status().isUnauthorized());
        mvc.perform(put(URL).contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isUnauthorized());
        mvc.perform(post(URL + "/complete")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/account/avatar")).andExpect(status().isUnauthorized());
        // there is no id in any URL, so another student's profile cannot even be addressed
        mvc.perform(get(URL + "/" + UUID.randomUUID()).cookie(a.session())).andExpect(status().isNotFound());
    }

    @Test
    void languageIsPersistedAndRestrictedToTheSupportedOnes() throws Exception {
        Student s = student();
        mvc.perform(put("/api/v1/account/language").cookie(s.session()).contentType(MediaType.APPLICATION_JSON).content("{\"language\":\"ru\"}"))
                .andExpect(status().isNoContent());
        mvc.perform(get(URL).cookie(s.session())).andExpect(jsonPath("$.profile.language").value("ru"));
        mvc.perform(put("/api/v1/account/language").cookie(s.session()).contentType(MediaType.APPLICATION_JSON).content("{\"language\":\"de\"}"))
                .andExpect(status().isBadRequest());
        // saving the profile without a language does not erase the chosen one
        save(s, doc(HIGH_SCHOOL, "")).andExpect(jsonPath("$.profile.language").value("ru"));
    }

    @Test
    void clearingTheProfileAndDeletingTheAccountRemoveEverythingIncludingThePicture() throws Exception {
        Student s = student();
        UUID id = jdbc.queryForObject("select id from user_account where email_normalized = ?", UUID.class, s.email());
        save(s, doc(HIGH_SCHOOL, "\"skills\":[{\"code\":\"java\"}]")).andExpect(status().isOk());
        mvc.perform(put("/api/v1/account/avatar").cookie(s.session()).contentType(MediaType.IMAGE_PNG).content(png(300, 200))).andExpect(status().isOk());
        mvc.perform(delete("/api/v1/account/profile").cookie(s.session())).andExpect(status().isNoContent());
        for (String t : new String[] {"student_profile", "student_education", "student_skill", "student_avatar"}) {
            assertThat(jdbc.queryForObject("select count(*) from " + t + " where account_id = ?", Integer.class, id)).as(t).isZero();
        }
        save(s, doc(HIGH_SCHOOL, "")).andExpect(status().isOk());
        mvc.perform(put("/api/v1/account/avatar").cookie(s.session()).contentType(MediaType.IMAGE_PNG).content(png(300, 200))).andExpect(status().isOk());
        mvc.perform(delete("/api/v1/account").cookie(s.session()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"password\":\"" + AccountApiIT.PASSWORD + "\"}")).andExpect(status().isNoContent());
        for (String t : new String[] {"student_profile", "student_education", "student_avatar"}) {
            assertThat(jdbc.queryForObject("select count(*) from " + t + " where account_id = ?", Integer.class, id)).as(t).isZero();
        }
    }

    // ------------------------------------------------------------------------------------------------ profile picture

    static byte[] png(int w, int h) throws Exception {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        for (int x = 0; x < w; x++) {
            for (int y = 0; y < h; y++) {
                img.setRGB(x, y, 0x80FF0000 | (x * 7 & 0xFF));
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    @Test
    void thePictureLivesInTheDatabaseNotOnTheServerDiskSoItSurvivesARestartAndAReplacementKeepsOneRow() throws Exception {
        Student a = student();
        mvc.perform(put("/api/v1/account/avatar").cookie(a.session()).contentType(MediaType.IMAGE_PNG).content(png(300, 300)))
                .andExpect(status().isOk());
        String id = jdbc.queryForObject("select id::text from user_account where email_normalized = ?", String.class, a.email().toLowerCase());
        // the bytes are a database row (hosted application disks are ephemeral; the database is not)
        Integer bytes = jdbc.queryForObject("select octet_length(data) from student_avatar where account_id = ?::uuid", Integer.class, id);
        assertThat(bytes).isBetween(100, 262144);
        // a fresh read (what a restarted application does) returns exactly the stored bytes
        byte[] stored = jdbc.queryForObject("select data from student_avatar where account_id = ?::uuid", byte[].class, id);
        byte[] served = mvc.perform(get("/api/v1/account/avatar").cookie(a.session())).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(served).isEqualTo(stored);
        // replacing keeps a single row and bumps the version; removing deletes it
        mvc.perform(put("/api/v1/account/avatar").cookie(a.session()).contentType(MediaType.IMAGE_PNG).content(png(120, 90)))
                .andExpect(status().isOk());
        assertThat(jdbc.queryForObject("select count(*) from student_avatar where account_id = ?::uuid", Integer.class, id)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select version from student_avatar where account_id = ?::uuid", Integer.class, id)).isEqualTo(2);
        mvc.perform(delete("/api/v1/account/avatar").cookie(a.session())).andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("select count(*) from student_avatar where account_id = ?::uuid", Integer.class, id)).isZero();
    }

    @Test
    void aPictureIsReencodedToASmallSquareJpegOwnedByTheUploader() throws Exception {
        Student a = student();
        Student b = student();
        // the account answer every screen reads (navigation included) carries the picture state
        mvc.perform(get("/api/v1/account/me").cookie(a.session())).andExpect(jsonPath("$.avatar.present").value(false));
        mvc.perform(get("/api/v1/account/avatar").cookie(a.session())).andExpect(status().isNotFound());
        mvc.perform(put("/api/v1/account/avatar").cookie(a.session()).contentType(MediaType.IMAGE_PNG).content(png(640, 480)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.present").value(true)).andExpect(jsonPath("$.version").value(1));
        byte[] stored = mvc.perform(get("/api/v1/account/avatar").cookie(a.session())).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(stored[0] & 0xFF).isEqualTo(0xFF); // JPEG
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(stored));
        assertThat(image.getWidth()).isEqualTo(256);
        assertThat(image.getHeight()).isEqualTo(256);
        assertThat(stored.length).isLessThan(60_000);
        // replacing bumps the version; the other student has no picture and cannot read this one
        mvc.perform(put("/api/v1/account/avatar").cookie(a.session()).contentType(MediaType.IMAGE_PNG).content(png(100, 100)))
                .andExpect(jsonPath("$.version").value(2));
        mvc.perform(get("/api/v1/account/avatar").cookie(b.session())).andExpect(status().isNotFound());
        mvc.perform(get(URL).cookie(a.session())).andExpect(jsonPath("$.avatar.present").value(true)).andExpect(jsonPath("$.avatar.version").value(2));
        mvc.perform(get("/api/v1/account/me").cookie(a.session())).andExpect(jsonPath("$.avatar.present").value(true))
                .andExpect(jsonPath("$.avatar.version").value(2));
        mvc.perform(delete("/api/v1/account/avatar").cookie(a.session())).andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/account/avatar").cookie(a.session())).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/account/me").cookie(a.session())).andExpect(jsonPath("$.avatar.present").value(false));
    }

    @Test
    void uploadsThatAreNotGenuinePicturesAreRefused() throws Exception {
        Student s = student();
        byte[] notAnImage = "<?php echo 'hello'; ?>".getBytes();
        mvc.perform(put("/api/v1/account/avatar").cookie(s.session()).contentType(MediaType.IMAGE_PNG).content(notAnImage))
                .andExpect(status().isUnsupportedMediaType()).andExpect(jsonPath("$.code").value("AVATAR_TYPE"));
        // the declared type and the file name mean nothing: a PNG header followed by garbage is not a picture either
        byte[] fake = new byte[2000];
        new Random(1).nextBytes(fake);
        System.arraycopy(new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A}, 0, fake, 0, 8);
        mvc.perform(put("/api/v1/account/avatar").cookie(s.session()).contentType(MediaType.IMAGE_JPEG).content(fake))
                .andExpect(status().isUnsupportedMediaType());
        // an SVG (scripts inside) and a GIF are not accepted
        mvc.perform(put("/api/v1/account/avatar").cookie(s.session()).contentType("image/jpeg")
                .content("<svg xmlns=\"http://www.w3.org/2000/svg\"><script>alert(1)</script></svg>".getBytes())).andExpect(status().isUnsupportedMediaType());
        mvc.perform(put("/api/v1/account/avatar").cookie(s.session()).contentType("image/png").content("GIF89a....".getBytes()))
                .andExpect(status().isUnsupportedMediaType());
        // too many bytes, too few pixels, too many pixels along one side
        byte[] big = new byte[AvatarService.MAX_UPLOAD_BYTES + 1];
        new Random(2).nextBytes(big);
        big[0] = (byte) 0xFF;
        big[1] = (byte) 0xD8;
        big[2] = (byte) 0xFF;
        mvc.perform(put("/api/v1/account/avatar").cookie(s.session()).contentType(MediaType.IMAGE_JPEG).content(big)).andExpect(status().isPayloadTooLarge());
        mvc.perform(put("/api/v1/account/avatar").cookie(s.session()).contentType(MediaType.IMAGE_PNG).content(png(16, 16))).andExpect(status().isBadRequest());
        mvc.perform(put("/api/v1/account/avatar").cookie(s.session()).contentType(MediaType.IMAGE_PNG).content(png(6100, 40)))
                .andExpect(status().isPayloadTooLarge()).andExpect(jsonPath("$.code").value("AVATAR_PIXELS"));
        mvc.perform(put("/api/v1/account/avatar").cookie(s.session()).contentType(MediaType.IMAGE_PNG).content(new byte[0])).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/account/avatar").cookie(s.session())).andExpect(status().isNotFound()); // nothing was stored
    }
}
