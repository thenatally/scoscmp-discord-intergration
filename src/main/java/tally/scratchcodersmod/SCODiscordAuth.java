package tally.scratchcodersmod;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.net.URI;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

public class SCODiscordAuth implements ModInitializer {
	public static final String MOD_ID = "sco-discord-auth";
	private MinecraftServer serverInstance = null;
	private WebSocketClient socketClient;
	private final Gson gson = new Gson();
	private final Map<UUID, ServerPlayerEntity> pendingAuthPlayers = new HashMap<>();
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
	private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
	private volatile boolean authAvailable = false;
	private Config config;
	// private final Map<UUID, ServerLoginNetworkHandler> pendingLoginHandlers = new
	// HashMap<>();
	private final Map<UUID, String> pendingKicks = new HashMap<>();
	// private final Map<UUID, AuthResult> authResults = new ConcurrentHashMap<>();
	private final Map<UUID, Boolean> pendingOps = new ConcurrentHashMap<>();
	private static SCODiscordAuth mod;
	private JsonObject remoteConfig = new JsonObject();
	private volatile long lastReconnectAttempt = 0;
	private static final long RECONNECT_INTERVAL_MS = 1000;

	public static SCODiscordAuth getMod() {
		return mod;
	}

	private static class Config {
		public String websocketUrl = "ws://localhost:25566";
	}

	private Config loadConfig() {
		File configFile = new File("config/sco-discord-auth.json");
		if (!configFile.exists()) {
			Config defaultConfig = new Config();
			try {
				configFile.getParentFile().mkdirs();
				FileWriter writer = new FileWriter(configFile);
				new Gson().toJson(defaultConfig, writer);
				writer.close();
				LOGGER.info("created default config");
			} catch (IOException e) {
				LOGGER.error("failed to write default config", e);
			}
			return defaultConfig;
		} else {
			try {
				FileReader reader = new FileReader(configFile);
				Config loadedConfig = new Gson().fromJson(reader, Config.class);
				reader.close();
				LOGGER.info("loaded config from file");
				return loadedConfig;
			} catch (IOException e) {
				LOGGER.error("failed to read config file", e);
				return new Config();
			}
		}
	}

	private void watchConfigFile() {

		Path configPath = Paths.get("config");
		Path filePath = configPath.resolve("sco-discord-auth.json");

		new Thread(() -> {
			try {
				WatchService watchService = FileSystems.getDefault().newWatchService();
				configPath.register(watchService, StandardWatchEventKinds.ENTRY_MODIFY);

				while (true) {
					WatchKey key = watchService.take();
					for (WatchEvent<?> event : key.pollEvents()) {
						WatchEvent.Kind<?> kind = event.kind();
						Path changed = (Path) event.context();
						if (kind == StandardWatchEventKinds.ENTRY_MODIFY && changed.equals(filePath.getFileName())) {
							LOGGER.info("config file changed");
							Config newConfig = loadConfig();
							if (!newConfig.websocketUrl.equals(config.websocketUrl)) {
								LOGGER.info("ws url changed");
								config = newConfig;
								reconnectWebSocket();
							} else {
								config = newConfig;
							}
						}
					}
					key.reset();
				}
			} catch (IOException | InterruptedException e) {
				LOGGER.error("failed to watch config file", e);
			}
		}, "ConfigWatcher").start();
	}

	private void reconnectWebSocket() {
		long now = System.currentTimeMillis();
		long delta = now - lastReconnectAttempt;

		if (delta < RECONNECT_INTERVAL_MS) {
			LOGGER.info("Connect attempt ignored: too soon since last attempt " + delta);
			return;
		}
		lastReconnectAttempt = now;

		try {
			if (socketClient != null) {
				socketClient.closeBlocking();
			}
		} catch (InterruptedException e) {
			LOGGER.error("ws close was interrupted", e);
		}
		connectWebSocket();
	}

	private void connectWebSocket() {

		try {
			socketClient = new WebSocketClient(new URI(config.websocketUrl)) {
				@Override
				public void onOpen(ServerHandshake handshakedata) {
					LOGGER.info("ws opened.");
					authAvailable = true;
				}

				@Override
				public void onMessage(String message) {
					LOGGER.info("ws message: {}", message);
					JsonObject json = gson.fromJson(message, JsonObject.class);

					if (!json.has("type"))
						return;
					String type = json.get("type").getAsString();

					switch (type) {
						// case "authentication.accept" -> {
						// UUID uuid = UUID.fromString(json.get("uuid").getAsString());
						// boolean opValue = json.has("op") && json.get("op").getAsBoolean();
						// pendingOps.put(uuid, opValue);
						// AuthResult result = authResults.get(uuid);
						// if (result != null) {
						// result.accepted = true;
						// result.latch.countDown();
						// }
						// serverInstance.execute(() -> {
						// ServerPlayerEntity player = pendingAuthPlayers.remove(uuid);
						// if (player != null) {
						// LOGGER.info("player {} authentication accepted, op value: {}",
						// player.getName().getString(), opValue);
						// }
						// });
						// }
						// case "authentication.deny" -> {
						// UUID uuid = UUID.fromString(json.get("uuid").getAsString());
						// String code = json.has("code") ? json.get("code").getAsString() :
						// "auth.denied";
						// AuthResult result = authResults.get(uuid);
						// if (result != null) {
						// result.accepted = false;
						// result.code = code;
						// result.latch.countDown();
						// }
						// serverInstance.execute(() -> {
						// ServerPlayerEntity player = pendingAuthPlayers.remove(uuid);
						// if (player != null) {
						// if (serverInstance.getPlayerManager().getPlayer(uuid) != null) {
						// player.networkHandler
						// .disconnect(Text.literal("Authentication denied: " + code));
						// LOGGER.info("player {} was denied auth: {}", player.getName().getString(),
						// code);
						// } else {
						// pendingKicks.put(uuid, "Authentication denied: " + code);
						// LOGGER.info("queued kick for player {}: {}", uuid, code);
						// }
						// } else {
						// pendingKicks.put(uuid, "Authentication denied: " + code);
						// LOGGER.info("queued kick for player {}: {}", uuid, code);
						// }
						// });
						// }
						case "kick" -> {
							UUID uuid = UUID.fromString(json.get("uuid").getAsString());
							serverInstance.execute(() -> {
								ServerPlayerEntity player = pendingAuthPlayers.remove(uuid);
								if (player == null) {
									player = serverInstance.getPlayerManager().getPlayer(uuid);
								}
								if (player != null) {
									if (serverInstance.getPlayerManager().getPlayer(uuid) != null) {
										String reason = json.has("reason") ? json.get("reason").getAsString()
												: "kicked by remote server";
										player.networkHandler.disconnect(Text.literal(reason));
										LOGGER.info("player {} was kicked: {}", player.getName().getString(), reason);
									} else {
										String reason = json.has("reason") ? json.get("reason").getAsString()
												: "kicked by remote server";
										pendingKicks.put(uuid, reason);
										LOGGER.info("queued kick for player {}: {}", uuid, reason);
									}
								} else {
									String reason = json.has("reason") ? json.get("reason").getAsString()
											: "kicked by remote server";
									pendingKicks.put(uuid, reason);
									LOGGER.info("queued kick for player {}: {}", uuid, reason);
								}
							});
						}
						case "deop" -> {
							serverInstance.execute(() -> {
								UUID uuid = UUID.fromString(json.get("uuid").getAsString());

								ServerPlayerEntity player = serverInstance.getPlayerManager().getPlayer(uuid);
								if (player != null) {
									serverInstance.getPlayerManager().removeFromOperators(player.getGameProfile());
									LOGGER.info("player {} was deopped by remote server.",
											player.getName().getString());
								}
							});
						}
						case "op" -> {
							serverInstance.execute(() -> {
								UUID uuid = UUID.fromString(json.get("uuid").getAsString());

								ServerPlayerEntity player = serverInstance.getPlayerManager().getPlayer(uuid);
								if (player != null) {
									serverInstance.getPlayerManager().addToOperators(player.getGameProfile());
									LOGGER.info("{} was opped by remote server.", player.getName().getString());
								}
							});
						}
						case "chat.message" -> {
							String sender = json.has("sender") ? json.get("sender").getAsString()
									: "Unknown";
							String messageContent = json.has("message") ? json.get("message").getAsString()
									: null;
							if (messageContent != null) {
								String color = json.has("color") ? json.get("color").getAsString() : "#FFFFFF";
								final int senderColor;
								int tempColor = 0xFFFFFF;
								try {
									if (color.startsWith("#")) {
										tempColor = Integer.parseInt(color.substring(1), 16);
									} else {
										tempColor = Integer.parseInt(color, 16);
									}
								} catch (Exception e) {
								}
								senderColor = tempColor;
								serverInstance.getPlayerManager().broadcast(
										Text.literal("[")
												.append(Text.literal("Discord")
														.styled(style -> style.withColor(0xAA00FF)))
												.append(Text.literal("] "))
												.append(Text.literal(sender)
														.styled(style -> style.withColor(senderColor)))
												.append(Text.literal(" > " + messageContent)),
										false);
							}
						}

						case "config.update" -> handleConfigUpdate(json);
						case "heartbeat" -> {
							authAvailable = true;
						}
						default -> LOGGER.warn("unhandled ws message type: {}", type);
					}
				}

				@Override
				public void onClose(int code, String reason, boolean remote) {
					LOGGER.warn("ws closed: {} - {}", code, reason);
					authAvailable = false;
					scheduler.schedule(() -> {
						reconnectWebSocket();
					}, 5, TimeUnit.SECONDS);
				}

				@Override
				public void onError(Exception ex) {
					LOGGER.error("ws error", ex);
					authAvailable = false;
					scheduler.schedule(() -> {
						reconnectWebSocket();
					}, 5, TimeUnit.SECONDS);
				}
			};

			socketClient.connect();
		} catch (Exception e) {
			LOGGER.error("failed to initialize ws client", e);
			authAvailable = false;
			scheduler.schedule(this::reconnectWebSocket, 5, TimeUnit.SECONDS);
		}
	}

	private void updateChannelDesc(String Text) {
		if (socketClient == null || !socketClient.isOpen()) {
			LOGGER.warn("WebSocket client is not connected, cannot update channel description.");
			return;
		}
		JsonObject request = new JsonObject();
		request.addProperty("type", "channel.description.update");
		request.addProperty("text", Text);
		socketClient.send(gson.toJson(request));
		LOGGER.info("Updated channel description: {}", Text);
	}

	@Override
	public void onInitialize() {
		mod = this;
		LOGGER.info("Hello Fabric world!");
		config = loadConfig();
		connectWebSocket();
		watchConfigFile();
		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			serverInstance = server;
		});

		var scheduler = Executors.newScheduledThreadPool(1);
		// attribute the_tally minecraft:scale base set 0.7
		// attribute zuzutnd minecraft:scale base set 0.5
		scheduler.scheduleAtFixedRate(() -> {
			try {
				System.out.println("Scheduled task started");
				var manager = serverInstance.getPlayerManager();
				var players = Arrays.stream(manager.getPlayerNames())
						.collect(Collectors.joining("\n"));
				updateChannelDesc(manager.getCurrentPlayerCount() + " players online:\n" + players);
			} catch (Exception e) {
				e.printStackTrace();
			}
		}, 0, 120, TimeUnit.SECONDS);

		ServerMessageEvents.CHAT_MESSAGE.register((message, sender, context) -> {
			if (!authAvailable) {
				return;
			}
			if (sender instanceof ServerPlayerEntity player) {
				JsonObject request = new JsonObject();
				request.addProperty("type", "chat.message");
				request.addProperty("uuid", player.getUuid().toString());
				request.addProperty("sender", player.getName().getString());
				request.addProperty("message", message.getContent().getString());
				socketClient.send(gson.toJson(request));
			}
		});
		ServerMessageEvents.ALLOW_CHAT_MESSAGE.register((signedMessage, player, params) -> true);
		// ServerMessageEvents.CHAT_MESSAGE.register(this::onChatMessage);
		ServerLivingEntityEvents.AFTER_DEATH.register((entity, damageSource) -> {
			if (!(entity instanceof ServerPlayerEntity player))
				return;
			if (!authAvailable)
				return;

			Text deathMessage = damageSource.getDeathMessage(player);

			JsonObject request = new JsonObject();
			request.addProperty("type", "player.death");
			request.addProperty("uuid", player.getUuid().toString());
			request.addProperty("player", player.getName().getString());
			request.addProperty("reason", damageSource.getName());
			request.addProperty("message", deathMessage.getString());

			socketClient.send(gson.toJson(request));
		});
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			ServerPlayerEntity player = handler.getPlayer();
			if (!authAvailable)
				return;

			JsonObject request = new JsonObject();
			request.addProperty("type", "player.join");
			request.addProperty("uuid", player.getUuid().toString());
			request.addProperty("player", player.getName().getString());
			socketClient.send(gson.toJson(request));

			Boolean shouldBeOp = pendingOps.remove(player.getUuid());
			if (shouldBeOp != null) {
				if (shouldBeOp) {
					server.getPlayerManager().addToOperators(player.getGameProfile());
					LOGGER.info("player {} opped on join (auth)", player.getName().getString());
				} else {
					server.getPlayerManager().removeFromOperators(player.getGameProfile());
					LOGGER.info("player {} deopped on join (auth)", player.getName().getString());
				}
			}

		});

		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			ServerPlayerEntity player = handler.getPlayer();
			if (!authAvailable)
				return;

			JsonObject request = new JsonObject();
			request.addProperty("type", "player.leave");
			request.addProperty("uuid", player.getUuid().toString());
			request.addProperty("player", player.getName().getString());
			socketClient.send(gson.toJson(request));

		});

	}

	private void sendConfigUpdate() {
		if (socketClient != null && socketClient.isOpen()) {
			JsonObject configJson = new JsonObject();
			configJson.addProperty("type", "config.update");
			configJson.add("config", gson.toJsonTree(remoteConfig));
			socketClient.send(gson.toJson(configJson));
		}
	}

	// On receiving config.update from JS, update local config
	private void handleConfigUpdate(JsonObject json) {
		if (json.has("config")) {
			remoteConfig = json.getAsJsonObject("config");
			LOGGER.info("Config updated from JS: {}", remoteConfig.toString());
		}
	}

	private void updatePendingAndSync(UUID uuid, String code) {
		if (!remoteConfig.has("pending"))
			remoteConfig.add("pending", new JsonObject());
		remoteConfig.getAsJsonObject("pending").addProperty(uuid.toString(), code);
		sendConfigUpdate();
	}

	public Object checkAuthentication(UUID uuid) {
		if (!authAvailable) {
			reconnectWebSocket();
			LOGGER.warn("Ws unavalable using outdated config");
		}
		String uuidStr = uuid.toString();
		if (remoteConfig.has("linked") && remoteConfig.getAsJsonObject("linked").has(uuidStr)) {
			boolean isTimedOut = remoteConfig.has("timedOut")
					&& remoteConfig.getAsJsonArray("timedOut").toString().contains(uuidStr);
			if (isTimedOut)
				return "You are currently timed out on Discord.";
			boolean isMod = remoteConfig.has("moderators")
					&& remoteConfig.getAsJsonArray("moderators").toString().contains(uuidStr);
			if (isMod)
				pendingOps.put(uuid, true);
			return true;
		}
		if (remoteConfig.has("pending") && remoteConfig.getAsJsonObject("pending").has(uuidStr)) {
			return "Use code " + remoteConfig.getAsJsonObject("pending").get(uuidStr).getAsString();
		}
		if (!authAvailable) {
			return "auth.unavailable cannot generate new code";
		}
		String code = UUID.randomUUID().toString().substring(0, 6);
		updatePendingAndSync(uuid, code);
		return "Use code " + code;
	}

}