package dev.finflow.importing.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.finflow.importing.storage.StatementStorage;
import dev.finflow.support.AbstractPostgresTest;
import dev.finflow.support.TestUsers;
import java.io.ByteArrayInputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;

@SpringBootTest
@AutoConfigureMockMvc
class ImportApiTest extends AbstractPostgresTest {

    private static final Charset WINDOWS_1251 = Charset.forName("windows-1251");

    @MockBean
    private StatementStorage storage;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbc;

    private final Map<String, byte[]> files = new ConcurrentHashMap<>();

    @BeforeEach
    void setUp() {
        files.clear();
        when(storage.store(any(), any(), any(), any(), any())).thenAnswer(call -> {
            String key = "statements/" + call.getArgument(0) + "/" + call.getArgument(1) + "/file.csv";
            files.put(key, call.getArgument(3));
            return key;
        });
        when(storage.open(anyString())).thenAnswer(call -> new ByteArrayInputStream(files.get(call.<String>getArgument(0))));
    }

    private ResultActions upload(String token, String format, String name, byte[] content) throws Exception {
        MockMultipartHttpServletRequestBuilder request = multipart("/api/v1/imports");
        if (content != null) {
            request.file(new MockMultipartFile("file", name, "text/csv", content));
        }
        if (format != null) {
            request.param("bankFormat", format);
        }
        return mvc.perform(request.header("Authorization", token));
    }

    private JsonNode awaitFinished(String token, String id) throws Exception {
        JsonNode[] last = new JsonNode[1];
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            String body = mvc.perform(get("/api/v1/imports/" + id).header("Authorization", token))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            last[0] = objectMapper.readTree(body);
            assertThat(last[0].get("status").asText()).isIn("DONE", "FAILED");
        });
        return last[0];
    }

    private String importId(ResultActions result) throws Exception {
        return objectMapper.readTree(result.andReturn().getResponse().getContentAsString()).get("id").asText();
    }

    private static byte[] tinkoff(String... lines) {
        return ("date,description,amount,currency,mcc\n" + String.join("\n", lines) + "\n")
                .getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void acceptsStatementAndProcessesItInTheBackground() throws Exception {
        String token = TestUsers.registerAndLogin(mvc, objectMapper);

        ResultActions response = upload(token, "TINKOFF", "october.csv", tinkoff(
                "2026-10-14,PYATEROCHKA 1234 MOSCOW RUS,-540.20,RUB,5411",
                "2026-10-14,YANDEX TAXI*SPB,-312.50,RUB,4121",
                "bad-date,Shop,-1,RUB,"));

        response.andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("UPLOADED"))
                .andExpect(jsonPath("$.bankFormat").value("TINKOFF"))
                .andExpect(jsonPath("$.originalName").value("october.csv"))
                .andExpect(jsonPath("$.errors").doesNotExist());
        JsonNode finished = awaitFinished(token, importId(response));

        assertThat(finished.get("status").asText()).isEqualTo("DONE");
        assertThat(finished.get("rowsImported").asInt()).isEqualTo(2);
        assertThat(finished.get("rowsFailed").asInt()).isEqualTo(1);
        assertThat(finished.get("rowsTotal").asInt()).isEqualTo(3);
        assertThat(finished.get("finishedAt").isNull()).isFalse();
        assertThat(finished.get("errors")).hasSize(1);
        assertThat(finished.get("errors").get(0).get("lineNumber").asInt()).isEqualTo(4);
        assertThat(finished.get("errors").get(0).get("reason").asText()).contains("Invalid date");
    }

    @Test
    void acceptsWindows1251StatementsFromSber() throws Exception {
        String token = TestUsers.registerAndLogin(mvc, objectMapper);
        String csv = "Дата операции;Описание;Сумма;Валюта;MCC\n14.10.2026;Пятерочка 77 МОСКВА;-100,50;RUB;5411\n";

        ResultActions response = upload(token, "SBER", "sber.csv", csv.getBytes(WINDOWS_1251));

        response.andExpect(status().isAccepted());
        JsonNode finished = awaitFinished(token, importId(response));
        assertThat(finished.get("rowsImported").asInt()).isEqualTo(1);
    }

    @Test
    void importsAreVisibleOnlyToTheirOwner() throws Exception {
        String alice = TestUsers.registerAndLogin(mvc, objectMapper);
        String bob = TestUsers.registerAndLogin(mvc, objectMapper);
        String id = importId(upload(alice, "TINKOFF", "a.csv", tinkoff("2026-10-14,SHOP,-1,RUB,")));

        mvc.perform(get("/api/v1/imports/" + id).header("Authorization", bob)).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/imports/" + UUID.randomUUID()).header("Authorization", alice))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/imports/" + id)).andExpect(status().isUnauthorized());
        awaitFinished(alice, id);
    }

    @Test
    void rejectsFileWithWrongHeaderBeforeStoringAnything() throws Exception {
        String token = TestUsers.registerAndLogin(mvc, objectMapper);

        upload(token, "SBER", "wrong.csv", tinkoff("2026-10-14,SHOP,-1,RUB,"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.type").value("https://finflow.dev/errors/invalid-statement"));

        verify(storage, never()).store(any(), any(), any(), any(), any());
    }

    @Test
    void rejectsEmptyAndHeaderOnlyFiles() throws Exception {
        String token = TestUsers.registerAndLogin(mvc, objectMapper);

        upload(token, "TINKOFF", "empty.csv", new byte[0])
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.type").value("https://finflow.dev/errors/empty-file"));
        upload(token, "TINKOFF", "header.csv", "date,description,amount,currency,mcc\n\n".getBytes(StandardCharsets.UTF_8))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.type").value("https://finflow.dev/errors/empty-statement"));
    }

    @Test
    void rejectsFilesLargerThanTenMegabytes() throws Exception {
        String token = TestUsers.registerAndLogin(mvc, objectMapper);
        byte[] big = new byte[10 * 1024 * 1024 + 1];

        upload(token, "TINKOFF", "big.csv", big)
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.type").value("https://finflow.dev/errors/file-too-large"));
    }

    @Test
    void rejectsMissingOrUnknownFormatAndMissingFile() throws Exception {
        String token = TestUsers.registerAndLogin(mvc, objectMapper);
        byte[] content = tinkoff("2026-10-14,SHOP,-1,RUB,");

        upload(token, null, "a.csv", content).andExpect(status().isBadRequest());
        upload(token, "UNKNOWN_BANK", "a.csv", content).andExpect(status().isBadRequest());
        upload(token, "TINKOFF", "a.csv", null).andExpect(status().isBadRequest());
    }

    @Test
    void requiresAuthentication() throws Exception {
        mvc.perform(multipart("/api/v1/imports").file(new MockMultipartFile("file", "a.csv", "text/csv",
                tinkoff("2026-10-14,SHOP,-1,RUB,"))).param("bankFormat", "TINKOFF"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void failedImportReportsTheReason() throws Exception {
        String token = TestUsers.registerAndLogin(mvc, objectMapper);
        String id = importId(upload(token, "TINKOFF", "a.csv", tinkoff("2026-10-14,SHOP,-1,RUB,")));
        awaitFinished(token, id);
        jdbc.update("update statement_imports set status = 'FAILED' where id = ?", UUID.fromString(id));
        jdbc.update("insert into import_errors (import_id, line_number, reason) values (?, 0, 'Storage lost the file')",
                UUID.fromString(id));

        mvc.perform(get("/api/v1/imports/" + id).header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.errors[?(@.lineNumber==0)].reason").value("Storage lost the file"));
    }
}
