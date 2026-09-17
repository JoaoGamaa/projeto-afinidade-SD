package afinidade;

import java.util.List;

/** Contratos imutáveis compartilhados pelos processos cliente e servidor. */
public final class Models {

    private Models() {}

    public record Artist(int id, String name) {}

    public record Profile(long id, String name, List<Integer> ratings, long version) {
        public Profile {
            ratings = List.copyOf(ratings);
        }
    }

    public record Catalog(List<Artist> artists, List<Profile> users) {
        public Catalog {
            artists = List.copyOf(artists);
            users = List.copyOf(users);
        }
    }

    public record Comparison(
        int artistId,
        String artistName,
        int targetRating,
        int neighborRating,
        int squaredDifference
    ) {}

    public record Neighbor(
        long userId,
        String name,
        double distance,
        int commonRatings,
        List<Comparison> comparisons
    ) {}

    public record Contribution(long userId, String name, int rating, double distance, double weight) {}

    public record Recommendation(
        int artistId,
        String artistName,
        double score,
        List<Contribution> contributions
    ) {}

    public record Result(
        long userId,
        long version,
        int k,
        List<Neighbor> neighbors,
        List<Recommendation> recommendations
    ) {}
}
