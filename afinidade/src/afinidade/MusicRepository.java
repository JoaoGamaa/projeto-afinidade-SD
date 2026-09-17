package afinidade;

import afinidade.Models.*;
import afinidade.Protocol.DomainException;
import java.util.*;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Repository
@org.springframework.context.annotation.Profile("!cliente")
public class MusicRepository {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    // Apenas operações de banco compartilham o lock. O cálculo ocorre fora dele.
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock(true);

    public MusicRepository(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(manager);
        initialize();
    }

    private void initialize() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS artists (id INT PRIMARY KEY, name VARCHAR(100) NOT NULL)");
        jdbc.execute(
            "CREATE TABLE IF NOT EXISTS listeners (id BIGINT PRIMARY KEY, name VARCHAR(100) NOT NULL, version BIGINT NOT NULL DEFAULT 0)"
        );
        jdbc.execute(
            "CREATE TABLE IF NOT EXISTS ratings (user_id BIGINT REFERENCES listeners(id), artist_id INT REFERENCES artists(id), score INT NOT NULL CHECK(score BETWEEN 0 AND 4), PRIMARY KEY(user_id, artist_id))"
        );
    }

    public Catalog snapshot() {
        lock.readLock().lock();
        try {
            List<Artist> artists = jdbc.query("SELECT * FROM artists ORDER BY id", (rs, n) ->
                new Artist(rs.getInt("id"), rs.getString("name"))
            );
            Map<Long, List<Integer>> scores = new HashMap<>();
            jdbc.query("SELECT user_id,score FROM ratings ORDER BY user_id,artist_id", rs -> {
                scores
                    .computeIfAbsent(rs.getLong("user_id"), ignored -> new ArrayList<>())
                    .add(rs.getInt("score"));
            });
            List<Profile> users = jdbc.query("SELECT * FROM listeners ORDER BY id", (rs, n) ->
                new Profile(
                    rs.getLong("id"),
                    rs.getString("name"),
                    scores.get(rs.getLong("id")),
                    rs.getLong("version")
                )
            );
            return new Catalog(artists, users);
        } finally {
            lock.readLock().unlock();
        }
    }

    public Profile update(long userId, List<Integer> ratings, long version) {
        if (
            ratings == null ||
            ratings.size() != 15 ||
            ratings.stream().anyMatch(x -> x == null || x < 0 || x > 4)
        ) throw new DomainException("INVALID_REQUEST", "Informe exatamente 15 notas inteiras entre 0 e 4.");
        if (version < 0) throw new DomainException(
            "INVALID_REQUEST",
            "A versão deve ser maior ou igual a zero."
        );
        List<Integer> copy = List.copyOf(ratings);
        lock.writeLock().lock();
        try {
            return transaction.execute(status -> {
                List<String> names = jdbc.queryForList(
                    "SELECT name FROM listeners WHERE id=?",
                    String.class,
                    userId
                );
                if (names.isEmpty()) throw new DomainException("NOT_FOUND", "Usuário não encontrado.");
                int updated = jdbc.update(
                    "UPDATE listeners SET version=version+1 WHERE id=? AND version=?",
                    userId,
                    version
                );
                if (updated == 0) throw new DomainException(
                    "CONFLICT",
                    "Este perfil foi alterado por outro cliente. Recarregue antes de salvar."
                );
                for (int a = 0; a < copy.size(); a++) jdbc.update(
                    "UPDATE ratings SET score=? WHERE user_id=? AND artist_id=?",
                    copy.get(a),
                    userId,
                    a + 1
                );
                return new Profile(userId, names.get(0), copy, version + 1);
            });
        } finally {
            lock.writeLock().unlock();
        }
    }
}
