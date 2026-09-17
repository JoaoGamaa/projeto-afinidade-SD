package afinidade;

import static org.assertj.core.api.Assertions.*;

import java.nio.file.Path;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class PersistenceTest {

    @TempDir
    Path directory;

    // Dados fictícios existem apenas nas bases temporárias dos testes.
    static void insertTestData(JdbcTemplate jdbc) {
        jdbc.update("DELETE FROM ratings");
        jdbc.update("DELETE FROM listeners");
        jdbc.update("DELETE FROM artists");
        for (int a = 1; a <= 15; a++) {
            jdbc.update("INSERT INTO artists VALUES (?,?)", a, "Artista " + a);
        }
        for (int u = 1; u <= 2; u++) {
            jdbc.update("INSERT INTO listeners(id,name) VALUES (?,?)", u, "Usuário " + u);
            for (int a = 1; a <= 15; a++) {
                jdbc.update("INSERT INTO ratings VALUES (?,?,?)", u, a, 3);
            }
        }
    }

    @Test
    void newDatabaseRemainsEmptyAfterReopening() {
        String url = "jdbc:h2:file:" + directory.resolve("empty").toAbsolutePath().toString().replace('\\', '/');
        for (int attempt = 0; attempt < 2; attempt++) {
            DriverManagerDataSource source = new DriverManagerDataSource(url, "sa", "");
            JdbcTemplate jdbc = new JdbcTemplate(source);
            MusicRepository repository = new MusicRepository(jdbc, new DataSourceTransactionManager(source));
            assertThat(repository.snapshot().artists()).isEmpty();
            assertThat(repository.snapshot().users()).isEmpty();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ratings", Integer.class)).isZero();
            jdbc.execute("SHUTDOWN");
        }
    }

    @Test
    void ratingsSurviveDatabaseReopening() {
        String url =
            "jdbc:h2:file:" + directory.resolve("music").toAbsolutePath().toString().replace('\\', '/');
        DriverManagerDataSource first = new DriverManagerDataSource(url, "sa", "");
        MusicRepository repository = new MusicRepository(
            new JdbcTemplate(first),
            new DataSourceTransactionManager(first)
        );
        insertTestData(new JdbcTemplate(first));
        var user = repository.snapshot().users().get(0);
        var scores = new ArrayList<>(user.ratings());
        scores.set(0, 1);
        repository.update(user.id(), scores, user.version());
        new JdbcTemplate(first).execute("SHUTDOWN");
        DriverManagerDataSource second = new DriverManagerDataSource(url, "sa", "");
        MusicRepository reopened = new MusicRepository(
            new JdbcTemplate(second),
            new DataSourceTransactionManager(second)
        );
        assertThat(reopened.snapshot().users()).hasSize(2);
        assertThat(reopened.snapshot().artists()).hasSize(15);
        assertThat(reopened.snapshot().users().get(0).ratings().get(0)).isEqualTo(1);
        assertThat(reopened.snapshot().users().get(0).version()).isEqualTo(1);
        new JdbcTemplate(second).execute("SHUTDOWN");
    }
}
