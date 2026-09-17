package afinidade;

import static org.assertj.core.api.Assertions.*;

import afinidade.Models.*;
import afinidade.Protocol.DomainException;
import java.util.*;
import org.junit.jupiter.api.Test;

class RecommendationServiceTest {

    private final RecommendationService service = new RecommendationService();
    private final List<Artist> artists = List.of(
        new Artist(1, "A"),
        new Artist(2, "B"),
        new Artist(3, "C"),
        new Artist(4, "D"),
        new Artist(5, "E")
    );

    private Profile profile(long id, Integer... ratings) {
        return new Profile(id, "Usuário " + id, List.of(ratings), 0);
    }

    @Test
    void excludesUnknownPositionsAndAppliesSquareRoot() {
        Neighbor neighbor = service
            .compare(profile(1, 4, 3, 0, 4, 2), profile(2, 3, 2, 4, 4, 0), artists)
            .orElseThrow();
        assertThat(neighbor.distance()).isEqualTo(Math.sqrt(2));
        assertThat(neighbor.commonRatings()).isEqualTo(3);
        assertThat(neighbor.comparisons()).extracting(Comparison::artistId).containsExactly(1, 2, 4);
    }

    @Test
    void noOverlapDoesNotMeanPerfectMatch() {
        assertThat(service.compare(profile(1, 4, 0, 0, 0, 0), profile(2, 0, 4, 0, 0, 0), artists)).isEmpty();
    }

    @Test
    void identicalKnownRatingsHaveZeroDistance() {
        assertThat(
            service
                .compare(profile(1, 4, 3, 0, 0, 0), profile(2, 4, 3, 4, 0, 0), artists)
                .orElseThrow()
                .distance()
        ).isZero();
    }

    @Test
    void recommendsOnlyUnknownArtistsWithWeightedScoreAtLeastThree() {
        Catalog catalog = new Catalog(
            artists,
            List.of(profile(1, 4, 0, 0, 0, 0), profile(2, 4, 4, 1, 0, 3), profile(3, 3, 3, 2, 0, 4))
        );
        Result result = service.recommend(catalog, 1, 2, 15);
        assertThat(result.recommendations()).extracting(Recommendation::artistId).containsExactly(2, 5);
        assertThat(result.recommendations().get(0).score()).isCloseTo(11.0 / 3.0, within(1e-12));
        assertThat(result.recommendations().get(0).contributions()).hasSize(2);
    }

    @Test
    void tiesPreferMoreCommonRatingsThenUserId() {
        Catalog catalog = new Catalog(
            artists,
            List.of(
                profile(1, 4, 3, 0, 0, 0),
                profile(4, 4, 0, 4, 0, 0),
                profile(3, 4, 3, 4, 0, 0),
                profile(2, 4, 3, 4, 0, 0)
            )
        );
        assertThat(service.recommend(catalog, 1, 3, 15).neighbors())
            .extracting(Neighbor::userId)
            .containsExactly(2L, 3L, 4L);
    }

    @Test
    void fullyRatedProfileHasNoRecommendations() {
        Catalog catalog = new Catalog(artists, List.of(profile(1, 4, 3, 2, 1, 4), profile(2, 4, 3, 2, 1, 4)));
        assertThat(service.recommend(catalog, 1, 3, 15).recommendations()).isEmpty();
    }

    @Test
    void unknownProfileAndInvalidParametersFailExplicitly() {
        Catalog catalog = new Catalog(artists, List.of(profile(1, 0, 0, 0, 0, 0)));
        assertThatThrownBy(() -> service.recommend(catalog, 99, 3, 5)).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> service.recommend(catalog, 1, 0, 5)).isInstanceOf(DomainException.class);
        assertThat(service.recommend(catalog, 1, 3, 5).neighbors()).isEmpty();
    }
}
