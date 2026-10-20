package com.reach.strategy.support;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.reach.strategy.identity.application.PlayerService;
import com.reach.strategy.diplomacy.application.CallToArmsService;
import com.reach.strategy.diplomacy.application.ConferenceService;
import com.reach.strategy.diplomacy.application.DiplomacyQueries;
import com.reach.strategy.diplomacy.application.DiplomacyService;
import com.reach.strategy.diplomacy.application.GiftService;
import com.reach.strategy.diplomacy.application.PeaceService;
import com.reach.strategy.game.application.GameResultService;
import com.reach.strategy.game.application.GameStateService;
import com.reach.strategy.game.application.LobbyService;
import com.reach.strategy.game.application.TurnService;
import com.reach.strategy.world.application.WorldService;
import com.reach.strategy.economy.application.EconomyService;
import com.reach.strategy.military.application.AttachmentService;
import com.reach.strategy.military.application.BattalionService;
import com.reach.strategy.military.application.MilitaryService;
import com.reach.strategy.research.application.ResearchService;
import com.reach.strategy.siege.application.SiegeService;
import com.reach.strategy.platform.security.ProblemSecurityHandlers;
import com.reach.strategy.platform.security.SecurityConfig;

import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.annotation.AliasFor;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Web-layer slice with the real security configuration and real JWT verification against
 * {@link TestJwtIssuer}. Application services are Mockito mocks; no database is involved.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@WebMvcTest
@Import({ SecurityConfig.class, ProblemSecurityHandlers.class })
@ContextConfiguration(initializers = TestJwtIssuer.PropertiesInitializer.class)
@MockitoBean(types = { PlayerService.class, LobbyService.class, TurnService.class, GameStateService.class,
		WorldService.class, EconomyService.class, MilitaryService.class, BattalionService.class,
		SiegeService.class, ResearchService.class, GameResultService.class, DiplomacyQueries.class,
		DiplomacyService.class, PeaceService.class, GiftService.class, CallToArmsService.class,
		ConferenceService.class, AttachmentService.class })
public @interface ApiSliceTest {

	@AliasFor(annotation = WebMvcTest.class, attribute = "controllers")
	Class<?>[] value() default {};

}
