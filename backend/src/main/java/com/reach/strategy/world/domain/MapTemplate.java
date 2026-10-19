package com.reach.strategy.world.domain;

import java.util.List;
import java.util.Map;

import com.reach.strategy.world.rules.MapDefinition;

/** A loaded map template: rules-relevant topology plus presentation-only geometry. */
public record MapTemplate(MapDefinition definition, Geometry geometry) {

	/** SVG data for drawing the map. Never consulted by game rules. */
	public record Geometry(String viewBox, Map<String, ProvinceShape> provinces, List<RiverShape> rivers) {

		public Geometry {
			provinces = Map.copyOf(provinces);
			rivers = List.copyOf(rivers);
		}

	}

	public record ProvinceShape(String svgPath, int labelX, int labelY) {
	}

	public record RiverShape(String a, String b, String svgPath) {
	}

}
