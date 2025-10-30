# Component Diagram (CoreProtectionService · NotificationListener · BannerService)

The diagram groups nodes by file and shows key functions and their relationships. It emphasizes the event loop inside CoreProtectionService that triggers protection and ultimately shows the banner.

```mermaid
flowchart TD
	%% ===== Notification Listener =====
	subgraph NL[AntiCenterNotificationListener.kt]
		NL_onNotificationPosted["onNotificationPosted(sbn)\n• filter own FGS notification\n• extract title/text and merge\n• startService(CoreProtectionService, ACTION_NOTIFICATION_TEXT, extras)"]
	end

	%% ===== Core Protection Service =====
	subgraph CPS[CoreProtectionService.kt]
		CPS_onStart["onStartCommand(intent)\nstartForeground()\nif action=ACTION_NOTIFICATION_TEXT -> handleNotificationTextIntent()"]
		CPS_handleIntent["handleNotificationTextIntent(intent)\nwrap NotificationTextEvent -> send to Channel"]
		CPS_consumer["startNotificationEventConsumer()\nfor (event in notificationChannel) LOOP\n· detect dialer incoming call\n· de-dup/state machine\n· handleIncomingCallFromNotification(event)\n· detect Zoom, etc."]
		CPS_handleCallNotif["handleIncomingCallFromNotification(event)\nextractPhoneNumber()\ncheckPhoneAllowlisted()\n[allowlisted?] skip\n[else] startCallProtection(number)"]
		CPS_checkAllow["checkPhoneAllowlisted(raw)\nvariants + repo.isValueInAllowlist(...)"]
		CPS_startProtect["startCallProtection(number)\nstate -> INCOMING\n(trigger risk checks / banner)"]
		CPS_overlayTest["maybeTriggerOverlayTest() (optional test)"]
	end

	%% ===== Overlay Banner Service =====
	subgraph OBS[OverlayBannerService.kt]
		OBS_onStart["onStartCommand(action)\nACTION_SHOW / ACTION_HIDE"]
		OBS_show["showOverlay(title, msg) -> WindowManager.addView()\nauto-dismiss timer"]
	end

	%% ===== Edges / Flow =====
	NL_onNotificationPosted --> CPS_onStart
	CPS_onStart --> CPS_handleIntent
	CPS_handleIntent --> CPS_consumer
	CPS_consumer -. loop .-> CPS_handleCallNotif
	CPS_handleCallNotif --> CPS_checkAllow
	CPS_checkAllow -- not in allowlist --> CPS_startProtect
	CPS_startProtect -- trigger banner --> OBS_onStart
	OBS_onStart --> OBS_show

	%% Allowlisted branch (no protection / no banner)
	CPS_checkAllow -- allowlisted (skip) --> CPS_consumer

	%% Optional test path
	CPS_overlayTest -. optional .-> OBS_onStart

	%% Style emphasis: highlight the loop node
	classDef loop fill:#FFF3CD,stroke:#F0AD4E,stroke-width:2px;
	class CPS_consumer loop;
```

Notes:

- The event loop lives in `startNotificationEventConsumer()` and consumes `Channel<NotificationTextEvent>`, which is the central trigger for protection features.
- Incoming call path: NotificationListener → CoreProtectionService (wraps event) → loop → extract number → allowlist check → if not allowlisted, enter protection and trigger `OverlayBannerService` to show a top banner.
- If allowlisted, the flow returns to the loop without starting protection or showing the banner.


