package com.reach.strategy.social.web;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;

import com.reach.strategy.identity.api.CurrentPlayer;
import com.reach.strategy.social.application.Notifications;

import com.reach.strategy.platform.ratelimit.RateLimited;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The caller's own notifications; there is no way to address anyone else's. */
@RateLimited("social-actions")
@RestController
@RequestMapping("/api/v1/notifications")
class NotificationController {

	private final Notifications notifications;

	NotificationController(Notifications notifications) {
		this.notifications = notifications;
	}

	@GetMapping
	Notifications.NotificationsView list(CurrentPlayer me) {
		return notifications.list(me);
	}

	/** The cheap poll: just the unread count. */
	@GetMapping("/unread-count")
	Map<String, Integer> unread(CurrentPlayer me) {
		return Map.of("unread", notifications.unread(me));
	}

	/** Marks the listed notifications read, or all of them when the list is empty or absent. */
	@PostMapping("/read")
	Notifications.NotificationsView read(CurrentPlayer me, @Valid @RequestBody(required = false) ReadRequest request) {
		return notifications.markRead(me, request == null || request.ids() == null ? List.of() : request.ids());
	}

	record ReadRequest(@Size(max = 100) List<UUID> ids) {
	}

}
