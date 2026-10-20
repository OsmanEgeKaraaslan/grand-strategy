package com.reach.strategy.support;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.util.List;
import java.util.UUID;

import com.jayway.jsonpath.JsonPath;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Drives the REST API as real players (signed tokens from {@link TestJwtIssuer}) for integration
 * tests. Methods return raw results so tests can assert status codes and bodies.
 */
public final class ApiDriver {

	private final MockMvc mockMvc;

	public ApiDriver(MockMvc mockMvc) {
		this.mockMvc = mockMvc;
	}

	/** A new authenticated user (fresh Supabase identity). */
	public static String newPlayer() {
		return TestJwtIssuer.get().newUserToken();
	}

	public MvcResult createGame(String token, String name) throws Exception {
		return perform(as(post("/api/v1/games"), token).contentType(MediaType.APPLICATION_JSON)
			.content("{\"name\":\"" + name + "\"}"));
	}

	/** @param mapKey null for the default map */
	public MvcResult createGame(String token, String name, int maxPlayers, String mapKey) throws Exception {
		return perform(as(post("/api/v1/games"), token).contentType(MediaType.APPLICATION_JSON)
			.content("{\"name\":\"" + name + "\",\"maxPlayers\":" + maxPlayers
					+ (mapKey == null ? "" : ",\"mapKey\":\"" + mapKey + "\"") + "}"));
	}

	public MvcResult maps(String token) throws Exception {
		return perform(as(get("/api/v1/maps"), token));
	}

	public MvcResult sendGold(String token, String gameId, String recipientNationId, long amountMg, UUID idempotencyKey)
			throws Exception {
		return command(post("/api/v1/games/" + gameId + "/diplomacy/gifts"), token,
				"{\"recipientNationId\":\"" + recipientNationId + "\",\"amountMg\":" + amountMg + "}", idempotencyKey);
	}

	public MvcResult join(String token, String inviteCode) throws Exception {
		return perform(as(post("/api/v1/games/join"), token).contentType(MediaType.APPLICATION_JSON)
			.content("{\"inviteCode\":\"" + inviteCode + "\"}"));
	}

	public MvcResult lobby(String token, String gameId) throws Exception {
		return perform(as(get("/api/v1/games/" + gameId), token));
	}

	public MvcResult chooseNation(String token, String gameId, String nationId) throws Exception {
		return perform(as(put("/api/v1/games/" + gameId + "/players/me/nation"), token)
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"nationId\":\"" + nationId + "\"}"));
	}

	public MvcResult start(String token, String gameId) throws Exception {
		return perform(as(post("/api/v1/games/" + gameId + "/start"), token));
	}

	public MvcResult state(String token, String gameId) throws Exception {
		return perform(as(get("/api/v1/games/" + gameId + "/state"), token));
	}

	public MvcResult endTurn(String token, String gameId, int expectedTurnNumber, UUID idempotencyKey) throws Exception {
		MockHttpServletRequestBuilder request = as(post("/api/v1/games/" + gameId + "/turn/end"), token)
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"expectedTurnNumber\":" + expectedTurnNumber + "}");
		if (idempotencyKey != null) {
			request.header("Idempotency-Key", idempotencyKey.toString());
		}
		return perform(request);
	}

	public MvcResult economy(String token, String gameId) throws Exception {
		return perform(as(get("/api/v1/games/" + gameId + "/economy"), token));
	}

	public MvcResult cityEconomy(String token, String gameId, String cityId) throws Exception {
		return perform(as(get("/api/v1/games/" + gameId + "/cities/" + cityId + "/economy"), token));
	}

	public MvcResult buildWorkshop(String token, String gameId, String cityId, int tier, UUID idempotencyKey)
			throws Exception {
		MockHttpServletRequestBuilder request = as(post("/api/v1/games/" + gameId + "/cities/" + cityId + "/workshops"), token)
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"tier\":" + tier + "}");
		if (idempotencyKey != null) {
			request.header("Idempotency-Key", idempotencyKey.toString());
		}
		return perform(request);
	}

	public MvcResult military(String token, String gameId) throws Exception {
		return perform(as(get("/api/v1/games/" + gameId + "/military"), token));
	}

	public MvcResult provinceMilitary(String token, String gameId, String provinceKey) throws Exception {
		return perform(as(get("/api/v1/games/" + gameId + "/provinces/" + provinceKey + "/military"), token));
	}

	public MvcResult battles(String token, String gameId) throws Exception {
		return perform(as(get("/api/v1/games/" + gameId + "/battles"), token));
	}

	public MvcResult battle(String token, String gameId, String battleId) throws Exception {
		return perform(as(get("/api/v1/games/" + gameId + "/battles/" + battleId), token));
	}

	public MvcResult recruit(String token, String gameId, String cityId, String unitType, UUID idempotencyKey)
			throws Exception {
		return command(post("/api/v1/games/" + gameId + "/cities/" + cityId + "/recruitments"), token,
				"{\"unitType\":\"" + unitType + "\"}", idempotencyKey);
	}

	public MvcResult move(String token, String gameId, List<String> battalionIds, String toProvinceKey,
			UUID idempotencyKey) throws Exception {
		return command(post("/api/v1/games/" + gameId + "/moves"), token,
				"{\"battalionIds\":" + jsonList(battalionIds) + ",\"toProvinceKey\":\"" + toProvinceKey + "\"}",
				idempotencyKey);
	}

	public MvcResult regroup(String token, String gameId, String name, List<String> battalionIds, UUID idempotencyKey)
			throws Exception {
		return command(post("/api/v1/games/" + gameId + "/armies"), token,
				"{\"name\":\"" + name + "\",\"battalionIds\":" + jsonList(battalionIds) + "}", idempotencyKey);
	}

	public MvcResult battalion(String token, String gameId, String battalionId) throws Exception {
		return perform(as(get("/api/v1/games/" + gameId + "/battalions/" + battalionId), token));
	}

	public MvcResult reinforce(String token, String gameId, String battalionId, int soldiers, UUID idempotencyKey)
			throws Exception {
		return command(post("/api/v1/games/" + gameId + "/battalions/" + battalionId + "/reinforcements"), token,
				"{\"soldiers\":" + soldiers + "}", idempotencyKey);
	}

	public MvcResult disband(String token, String gameId, String battalionId, UUID idempotencyKey) throws Exception {
		return command(post("/api/v1/games/" + gameId + "/battalions/" + battalionId + "/disband"), token, "{}",
				idempotencyKey);
	}

	public MvcResult sieges(String token, String gameId) throws Exception {
		return perform(as(get("/api/v1/games/" + gameId + "/sieges"), token));
	}

	public MvcResult siege(String token, String gameId, String siegeId) throws Exception {
		return perform(as(get("/api/v1/games/" + gameId + "/sieges/" + siegeId), token));
	}

	public MvcResult fortification(String token, String gameId, String provinceKey) throws Exception {
		return perform(as(get("/api/v1/games/" + gameId + "/provinces/" + provinceKey + "/fortification"), token));
	}

	public MvcResult buildFortress(String token, String gameId, String cityId, int targetLevel, UUID idempotencyKey)
			throws Exception {
		return command(post("/api/v1/games/" + gameId + "/cities/" + cityId + "/fortress"), token,
				"{\"targetLevel\":" + targetLevel + "}", idempotencyKey);
	}

	public MvcResult repairFortress(String token, String gameId, String cityId, UUID idempotencyKey) throws Exception {
		return command(post("/api/v1/games/" + gameId + "/cities/" + cityId + "/fortress/repair"), token, "{}",
				idempotencyKey);
	}

	public MvcResult repairWorkshop(String token, String gameId, String cityId, String buildingId, UUID idempotencyKey)
			throws Exception {
		return command(post("/api/v1/games/" + gameId + "/cities/" + cityId + "/workshops/" + buildingId + "/repair"),
				token, "{}", idempotencyKey);
	}

	public MvcResult research(String token, String gameId) throws Exception {
		return perform(as(get("/api/v1/games/" + gameId + "/research"), token));
	}

	public MvcResult cityInstitutions(String token, String gameId, String cityId) throws Exception {
		return perform(as(get("/api/v1/games/" + gameId + "/cities/" + cityId + "/institutions"), token));
	}

	public MvcResult buildInstitution(String token, String gameId, String cityId, String type, UUID idempotencyKey)
			throws Exception {
		return command(post("/api/v1/games/" + gameId + "/cities/" + cityId + "/institutions"), token,
				"{\"type\":\"" + type + "\"}", idempotencyKey);
	}

	public MvcResult unlockTechnology(String token, String gameId, String track, int targetLevel, UUID idempotencyKey)
			throws Exception {
		return command(post("/api/v1/games/" + gameId + "/research/technologies"), token,
				"{\"track\":\"" + track + "\",\"targetLevel\":" + targetLevel + "}", idempotencyKey);
	}

	public MvcResult unlockDoctrine(String token, String gameId, String doctrine, UUID idempotencyKey) throws Exception {
		return command(post("/api/v1/games/" + gameId + "/research/doctrines"), token,
				"{\"doctrine\":\"" + doctrine + "\"}", idempotencyKey);
	}

	public MvcResult upgradeWorkshop(String token, String gameId, String cityId, String buildingId, UUID idempotencyKey)
			throws Exception {
		return command(post("/api/v1/games/" + gameId + "/cities/" + cityId + "/workshops/" + buildingId + "/upgrade"),
				token, "{}", idempotencyKey);
	}

	private MvcResult command(MockHttpServletRequestBuilder request, String token, String json, UUID idempotencyKey)
			throws Exception {
		MockHttpServletRequestBuilder withBody = as(request, token).contentType(MediaType.APPLICATION_JSON).content(json);
		if (idempotencyKey != null) {
			withBody.header("Idempotency-Key", idempotencyKey.toString());
		}
		return perform(withBody);
	}

	// --- coalitions and conferences (Phase 10B) -------------------------------------------------

	public MvcResult attach(String token, String gameId, List<String> battalionIds, String commanderNationId,
			UUID idempotencyKey) throws Exception {
		return command(post("/api/v1/games/" + gameId + "/attachments"), token, "{\"battalionIds\":" + jsonList(battalionIds)
				+ ",\"commanderNationId\":\"" + commanderNationId + "\"}", idempotencyKey);
	}

	public MvcResult release(String token, String gameId, List<String> battalionIds, UUID idempotencyKey)
			throws Exception {
		return command(post("/api/v1/games/" + gameId + "/attachments/release"), token,
				"{\"battalionIds\":" + jsonList(battalionIds) + "}", idempotencyKey);
	}

	public MvcResult callToArms(String token, String gameId, String warId, String allyNationId, UUID idempotencyKey)
			throws Exception {
		return command(post("/api/v1/games/" + gameId + "/diplomacy/wars/" + warId + "/calls"), token,
				"{\"allyNationId\":\"" + allyNationId + "\"}", idempotencyKey);
	}

	public MvcResult answerCall(String token, String gameId, String callId, boolean accept, UUID idempotencyKey)
			throws Exception {
		return command(post("/api/v1/games/" + gameId + "/diplomacy/calls/" + callId + "/answer"), token,
				"{\"accept\":" + accept + "}", idempotencyKey);
	}

	/** @param kind GENERAL or SEPARATE */
	public MvcResult requestConference(String token, String gameId, String warId, String kind, UUID idempotencyKey)
			throws Exception {
		return command(post("/api/v1/games/" + gameId + "/diplomacy/wars/" + warId + "/conferences"), token,
				"{\"kind\":\"" + kind + "\"}", idempotencyKey);
	}

	public MvcResult answerConference(String token, String gameId, String conferenceId, boolean accept,
			UUID idempotencyKey) throws Exception {
		return command(post("/api/v1/games/" + gameId + "/diplomacy/conferences/" + conferenceId + "/answer"), token,
				"{\"accept\":" + accept + "}", idempotencyKey);
	}

	public MvcResult cancelConference(String token, String gameId, String conferenceId, UUID idempotencyKey)
			throws Exception {
		return command(post("/api/v1/games/" + gameId + "/diplomacy/conferences/" + conferenceId + "/cancel"), token,
				"{}", idempotencyKey);
	}

	/** @param termsJson a JSON array of terms */
	public MvcResult submitDraft(String token, String gameId, String conferenceId, int expectedRevision,
			String termsJson, UUID idempotencyKey) throws Exception {
		return command(post("/api/v1/games/" + gameId + "/diplomacy/conferences/" + conferenceId + "/drafts"), token,
				"{\"expectedRevision\":" + expectedRevision + ",\"terms\":" + termsJson + "}", idempotencyKey);
	}

	/** {@code action}: approve or reject. */
	public MvcResult decideDraft(String token, String gameId, String conferenceId, int revision, String action,
			UUID idempotencyKey) throws Exception {
		return command(post("/api/v1/games/" + gameId + "/diplomacy/conferences/" + conferenceId + "/revisions/"
				+ revision + "/" + action), token, "{}", idempotencyKey);
	}

	public MvcResult conference(String token, String gameId, String conferenceId) throws Exception {
		return perform(as(get("/api/v1/games/" + gameId + "/diplomacy/conferences/" + conferenceId), token));
	}

	// --- diplomacy (Phase 9) -------------------------------------------------------------------

	public MvcResult diplomacy(String token, String gameId) throws Exception {
		return perform(as(get("/api/v1/games/" + gameId + "/diplomacy"), token));
	}

	public MvcResult war(String token, String gameId, String warId) throws Exception {
		return perform(as(get("/api/v1/games/" + gameId + "/diplomacy/wars/" + warId), token));
	}

	public MvcResult declareWar(String token, String gameId, String targetNationId, UUID idempotencyKey)
			throws Exception {
		return command(post("/api/v1/games/" + gameId + "/diplomacy/wars"), token,
				"{\"targetNationId\":\"" + targetNationId + "\"}", idempotencyKey);
	}

	public MvcResult proposeAlliance(String token, String gameId, String nationId, String kind, UUID idempotencyKey)
			throws Exception {
		return command(post("/api/v1/games/" + gameId + "/diplomacy/alliances"), token,
				"{\"nationId\":\"" + nationId + "\",\"kind\":\"" + kind + "\"}", idempotencyKey);
	}

	/** {@code action}: accept, reject or terminate. */
	public MvcResult alliance(String token, String gameId, String allianceId, String action, UUID idempotencyKey)
			throws Exception {
		return command(post("/api/v1/games/" + gameId + "/diplomacy/alliances/" + allianceId + "/" + action), token, "{}",
				idempotencyKey);
	}

	/** @param termsJson a JSON array of terms */
	public MvcResult proposePeace(String token, String gameId, String warId, String termsJson, UUID idempotencyKey)
			throws Exception {
		return command(post("/api/v1/games/" + gameId + "/diplomacy/peace-proposals"), token,
				"{\"warId\":\"" + warId + "\",\"terms\":" + termsJson + "}", idempotencyKey);
	}

	public MvcResult checkPeace(String token, String gameId, String warId, String termsJson) throws Exception {
		return command(post("/api/v1/games/" + gameId + "/diplomacy/peace-proposals/check"), token,
				"{\"warId\":\"" + warId + "\",\"terms\":" + termsJson + "}", null);
	}

	/** {@code action}: accept, reject or cancel. */
	public MvcResult peace(String token, String gameId, String proposalId, String action, UUID idempotencyKey)
			throws Exception {
		return command(post("/api/v1/games/" + gameId + "/diplomacy/peace-proposals/" + proposalId + "/" + action),
				token, "{}", idempotencyKey);
	}

	public MvcResult counterPeace(String token, String gameId, String proposalId, String termsJson, UUID idempotencyKey)
			throws Exception {
		return command(post("/api/v1/games/" + gameId + "/diplomacy/peace-proposals/" + proposalId + "/counter"), token,
				"{\"terms\":" + termsJson + "}", idempotencyKey);
	}

	public MvcResult peaceProposals(String token, String gameId) throws Exception {
		return perform(as(get("/api/v1/games/" + gameId + "/diplomacy/peace-proposals"), token));
	}

	public MvcResult peaceProposal(String token, String gameId, String proposalId) throws Exception {
		return perform(as(get("/api/v1/games/" + gameId + "/diplomacy/peace-proposals/" + proposalId), token));
	}

	public MvcResult treaties(String token, String gameId) throws Exception {
		return perform(as(get("/api/v1/games/" + gameId + "/diplomacy/treaties"), token));
	}

	public MvcResult tributes(String token, String gameId) throws Exception {
		return perform(as(get("/api/v1/games/" + gameId + "/diplomacy/tributes"), token));
	}

	public MvcResult diplomacyEvents(String token, String gameId) throws Exception {
		return perform(as(get("/api/v1/games/" + gameId + "/diplomacy/events"), token));
	}

	public MvcResult result(String token, String gameId) throws Exception {
		return perform(as(get("/api/v1/games/" + gameId + "/result"), token));
	}

	/** A request without any credentials (for the security tests). */
	public MvcResult anonymous(String path) throws Exception {
		return perform(get(path));
	}

	private static String jsonList(List<String> values) {
		return values.stream().map(v -> "\"" + v + "\"").collect(java.util.stream.Collectors.joining(",", "[", "]"));
	}

	/** The city id of a province in a started game, from the game state. */
	public String cityId(String token, String gameId, String provinceKey) throws Exception {
		List<String> ids = JsonPath.read(body(state(token, gameId)), "$.provinces[?(@.key == '" + provinceKey + "')].city.id");
		return ids.getFirst();
	}

	public MvcResult map(String token, String mapId) throws Exception {
		return perform(as(get("/api/v1/maps/" + mapId), token));
	}

	/** Host and guest in a READY two-player lobby, nations not yet chosen. */
	public Lobby readyLobby(String name) throws Exception {
		String host = newPlayer();
		String guest = newPlayer();
		MvcResult created = createGame(host, name);
		String gameId = read(created, "$.id");
		join(guest, read(created, "$.inviteCode"));
		List<String> nationIds = JsonPath.read(body(created), "$.nations[*].id");
		return new Lobby(gameId, host, guest, nationIds.get(0), nationIds.get(1), read(created, "$.mapId"));
	}

	/** A started two-player game: host has the first nation, guest the second; host's turn. */
	public Lobby startedGame(String name) throws Exception {
		Lobby lobby = readyLobby(name);
		chooseNation(lobby.host(), lobby.gameId(), lobby.firstNationId());
		chooseNation(lobby.guest(), lobby.gameId(), lobby.secondNationId());
		start(lobby.host(), lobby.gameId());
		return lobby;
	}

	public record Lobby(String gameId, String host, String guest, String firstNationId, String secondNationId,
			String mapId) {
	}

	public static String body(MvcResult result) throws Exception {
		return result.getResponse().getContentAsString();
	}

	public static <T> T read(MvcResult result, String path) throws Exception {
		return JsonPath.read(body(result), path);
	}

	public static int status(MvcResult result) {
		return result.getResponse().getStatus();
	}

	private MvcResult perform(MockHttpServletRequestBuilder request) throws Exception {
		return mockMvc.perform(request).andReturn();
	}

	private static MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request, String token) {
		return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
	}

}
