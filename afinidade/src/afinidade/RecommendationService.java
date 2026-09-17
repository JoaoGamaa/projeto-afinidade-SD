package afinidade;

import afinidade.Models.*;
import afinidade.Protocol.DomainException;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
@org.springframework.context.annotation.Profile("!cliente")
public class RecommendationService {

    /** Distância euclidiana bruta, apenas nas dimensões conhecidas por ambos. */
    public Optional<Neighbor> compare(Profile target, Profile candidate, List<Artist> artists) {
        List<Comparison> terms = new ArrayList<>();
        int sum = 0;
        for (int i = 0; i < artists.size(); i++) {
            int a = target.ratings().get(i),
                b = candidate.ratings().get(i);
            if (a == 0 || b == 0) continue;
            int square = (a - b) * (a - b);
            sum += square;
            terms.add(new Comparison(artists.get(i).id(), artists.get(i).name(), a, b, square));
        }
        if (terms.isEmpty()) return Optional.empty();
        return Optional.of(
            new Neighbor(candidate.id(), candidate.name(), Math.sqrt(sum), terms.size(), List.copyOf(terms))
        );
    }

    public Result recommend(Catalog catalog, long userId, int k, int limit) {
        if (k < 1 || k > 9 || limit < 1 || limit > 15) throw new DomainException(
            "INVALID_REQUEST",
            "k deve estar entre 1 e 9; limit, entre 1 e 15."
        );
        Profile target = catalog
            .users()
            .stream()
            .filter(u -> u.id() == userId)
            .findFirst()
            .orElseThrow(() -> new DomainException("NOT_FOUND", "Usuário não encontrado."));
        List<Neighbor> neighbors = catalog
            .users()
            .stream()
            .filter(u -> u.id() != userId)
            .map(u -> compare(target, u, catalog.artists()))
            .flatMap(Optional::stream)
            .sorted(
                Comparator.comparingDouble(Neighbor::distance)
                    .thenComparing(Comparator.comparingInt(Neighbor::commonRatings).reversed())
                    .thenComparingLong(Neighbor::userId)
            )
            .limit(k)
            .toList();
        Map<Long, Profile> profiles = new HashMap<>();
        catalog.users().forEach(u -> profiles.put(u.id(), u));
        List<Recommendation> recommendations = new ArrayList<>();
        for (int i = 0; i < catalog.artists().size(); i++) {
            if (target.ratings().get(i) != 0) continue;
            List<Contribution> contributions = new ArrayList<>();
            double weightedSum = 0,
                totalWeight = 0;
            for (Neighbor neighbor : neighbors) {
                int rating = profiles.get(neighbor.userId()).ratings().get(i);
                if (rating == 0) continue;
                double weight = 1.0 / (1.0 + neighbor.distance());
                weightedSum += weight * rating;
                totalWeight += weight;
                contributions.add(
                    new Contribution(neighbor.userId(), neighbor.name(), rating, neighbor.distance(), weight)
                );
            }
            if (totalWeight == 0) continue;
            double score = weightedSum / totalWeight;
            // Notas 1 e 2 participam da média, mas não geram sugestões negativas.
            if (score < 3.0) continue;
            Artist artist = catalog.artists().get(i);
            recommendations.add(
                new Recommendation(artist.id(), artist.name(), score, List.copyOf(contributions))
            );
        }
        recommendations.sort(
            Comparator.comparingDouble(Recommendation::score)
                .reversed()
                .thenComparingInt(Recommendation::artistId)
        );
        return new Result(
            userId,
            target.version(),
            k,
            neighbors,
            recommendations.stream().limit(limit).toList()
        );
    }
}
