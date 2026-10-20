package com.reach.strategy.world.rules;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Checks a map's adjacency graph against its province geometry (Phase 10). Province outlines are
 * SVG paths made of straight segments ({@code M x y L x y … Z}, possibly several rings); neighbouring
 * provinces share identical border points, so a shared border is a set of identical segments.
 * Two provinces must be adjacent exactly when their shared border is at least the map's
 * {@code minBorderLength} long: a touching corner (length 0) or a sliver below the threshold is not
 * a land border, and water never creates adjacency because sea is not a province.
 */
public final class MapGeometry {

	private MapGeometry() {
	}

	/** An unordered pair of province keys, a &lt; b. */
	public record Pair(String a, String b) implements Comparable<Pair> {

		public static Pair of(String x, String y) {
			return x.compareTo(y) < 0 ? new Pair(x, y) : new Pair(y, x);
		}

		@Override
		public int compareTo(Pair other) {
			int c = a.compareTo(other.a);
			return c != 0 ? c : b.compareTo(other.b);
		}

	}

	/** Shared border length per pair of provinces, from their outlines. */
	public static Map<Pair, Double> sharedBorders(Map<String, String> pathByProvince, List<String> problems) {
		Map<String, List<String>> owners = new HashMap<>();
		Map<String, Double> lengths = new HashMap<>();
		for (var entry : new TreeMap<>(pathByProvince).entrySet()) {
			for (double[][] ring : rings(entry.getValue(), entry.getKey(), problems)) {
				for (int i = 0; i < ring.length; i++) {
					double[] p = ring[i];
					double[] q = ring[(i + 1) % ring.length];
					if (p[0] == q[0] && p[1] == q[1]) {
						continue;
					}
					String key = segmentKey(p, q);
					owners.computeIfAbsent(key, k -> new ArrayList<>()).add(entry.getKey());
					lengths.put(key, Math.hypot(p[0] - q[0], p[1] - q[1]));
				}
			}
		}
		Map<Pair, Double> shared = new TreeMap<>();
		for (var entry : owners.entrySet()) {
			List<String> provinces = entry.getValue();
			if (provinces.size() > 2) {
				problems.add("Border segment shared by more than two provinces: " + new TreeSet<>(provinces));
				continue;
			}
			if (provinces.size() == 2) {
				if (provinces.get(0).equals(provinces.get(1))) {
					problems.add("Province overlaps itself: " + provinces.get(0));
					continue;
				}
				shared.merge(Pair.of(provinces.get(0), provinces.get(1)), lengths.get(entry.getKey()), Double::sum);
			}
		}
		return shared;
	}

	/**
	 * Every adjacency must stand on a shared land border of at least {@code minBorderLength}, and
	 * every such border must be an adjacency.
	 */
	public static List<String> validate(MapDefinition map, Map<String, String> pathByProvince) {
		List<String> problems = new ArrayList<>();
		if (map.minBorderLength() == null) {
			return problems;
		}
		for (MapDefinition.Province province : map.provinces()) {
			if (!pathByProvince.containsKey(province.key())) {
				problems.add("Province has no outline: " + province.key());
			}
		}
		Map<Pair, Double> shared = sharedBorders(pathByProvince, problems);
		Set<Pair> adjacent = new TreeSet<>();
		map.adjacencies().forEach(edge -> adjacent.add(Pair.of(edge.a(), edge.b())));
		for (Pair pair : adjacent) {
			double length = shared.getOrDefault(pair, 0.0);
			if (length < map.minBorderLength()) {
				problems.add("Adjacent provinces without a land border of " + map.minBorderLength() + ": " + pair.a() + " - "
						+ pair.b() + " (" + Math.round(length) + ")");
			}
		}
		for (var entry : shared.entrySet()) {
			if (entry.getValue() >= map.minBorderLength() && !adjacent.contains(entry.getKey())) {
				problems.add("Land border without adjacency: " + entry.getKey().a() + " - " + entry.getKey().b() + " ("
						+ Math.round(entry.getValue()) + ")");
			}
		}
		return problems;
	}

	private static String segmentKey(double[] p, double[] q) {
		String x = fmt(p);
		String y = fmt(q);
		return x.compareTo(y) < 0 ? x + ";" + y : y + ";" + x;
	}

	private static String fmt(double[] p) {
		return p[0] + "," + p[1];
	}

	/** Parses {@code M x y L x y … Z} rings (absolute commands only). */
	static List<double[][]> rings(String path, String province, List<String> problems) {
		List<double[][]> rings = new ArrayList<>();
		List<double[]> current = new ArrayList<>();
		String[] tokens = path.trim().replaceAll("([MLZmlz])", " $1 ").trim().split("[\\s,]+");
		int i = 0;
		while (i < tokens.length) {
			String t = tokens[i];
			switch (t) {
				case "M" -> {
					if (!current.isEmpty()) {
						rings.add(current.toArray(double[][]::new));
					}
					current = new ArrayList<>();
					current.add(new double[] { Double.parseDouble(tokens[i + 1]), Double.parseDouble(tokens[i + 2]) });
					i += 3;
				}
				case "L" -> {
					current.add(new double[] { Double.parseDouble(tokens[i + 1]), Double.parseDouble(tokens[i + 2]) });
					i += 3;
				}
				case "Z" -> {
					if (!current.isEmpty()) {
						rings.add(current.toArray(double[][]::new));
					}
					current = new ArrayList<>();
					i++;
				}
				default -> {
					// Implicit lineto: consecutive coordinate pairs after an L.
					if (i + 1 < tokens.length && !current.isEmpty()) {
						current.add(new double[] { Double.parseDouble(t), Double.parseDouble(tokens[i + 1]) });
						i += 2;
					}
					else {
						problems.add("Unsupported path data in " + province + ": " + t);
						return rings;
					}
				}
			}
		}
		if (!current.isEmpty()) {
			rings.add(current.toArray(double[][]::new));
		}
		return rings;
	}

}
