/*
 * Copyright (c) 2019-2020 GeyserMC. http://geysermc.org
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 *
 * @author GeyserMC
 * @link https://github.com/GeyserMC/Geyser
 */

package org.geysermc.connector.network.session;

import com.github.steveice10.mc.auth.data.GameProfile;
import com.github.steveice10.mc.auth.exception.request.InvalidCredentialsException;
import com.github.steveice10.mc.auth.exception.request.RequestException;
import com.github.steveice10.mc.protocol.MinecraftProtocol;
import com.github.steveice10.mc.protocol.data.SubProtocol;
import com.github.steveice10.mc.protocol.data.game.entity.metadata.Position;
import com.github.steveice10.mc.protocol.data.game.entity.player.GameMode;
import com.github.steveice10.mc.protocol.data.game.window.VillagerTrade;
import com.github.steveice10.mc.protocol.data.message.MessageSerializer;
import com.github.steveice10.mc.protocol.packet.handshake.client.HandshakePacket;
import com.github.steveice10.mc.protocol.packet.ingame.client.ClientPluginMessagePacket;
import com.github.steveice10.mc.protocol.packet.ingame.client.world.ClientTeleportConfirmPacket;
import com.github.steveice10.mc.protocol.packet.ingame.server.ServerRespawnPacket;
import com.github.steveice10.mc.protocol.packet.login.server.LoginSuccessPacket;
import com.github.steveice10.packetlib.Client;
import com.github.steveice10.packetlib.event.session.*;
import com.github.steveice10.packetlib.packet.Packet;
import com.github.steveice10.packetlib.tcp.TcpSessionFactory;
import com.nukkitx.math.GenericMath;
import com.nukkitx.math.vector.*;
import com.nukkitx.protocol.bedrock.BedrockPacket;
import com.nukkitx.protocol.bedrock.BedrockServerSession;
import com.nukkitx.protocol.bedrock.data.*;
import com.nukkitx.protocol.bedrock.data.command.CommandPermission;
import com.nukkitx.protocol.bedrock.packet.*;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMaps;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2LongMap;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import lombok.Getter;
import lombok.Setter;
import org.geysermc.common.window.CustomFormWindow;
import org.geysermc.common.window.FormWindow;
import org.geysermc.connector.GeyserConnector;
import org.geysermc.connector.command.CommandSender;
import org.geysermc.connector.common.AuthType;
import org.geysermc.connector.entity.Entity;
import org.geysermc.connector.entity.PlayerEntity;
import org.geysermc.connector.event.EventManager;
import org.geysermc.connector.event.EventResult;
import org.geysermc.connector.event.events.geyser.GeyserLoginEvent;
import org.geysermc.connector.event.events.network.SessionConnectEvent;
import org.geysermc.connector.event.events.network.SessionDisconnectEvent;
import org.geysermc.connector.event.events.packet.DownstreamPacketReceiveEvent;
import org.geysermc.connector.event.events.packet.DownstreamPacketSendEvent;
import org.geysermc.connector.event.events.packet.UpstreamPacketSendEvent;
import org.geysermc.connector.inventory.PlayerInventory;
import org.geysermc.connector.network.remote.RemoteServer;
import org.geysermc.connector.network.session.auth.AuthData;
import org.geysermc.connector.network.session.auth.BedrockClientData;
import org.geysermc.connector.network.session.cache.*;
import org.geysermc.connector.network.translators.BiomeTranslator;
import org.geysermc.connector.network.translators.EntityIdentifierRegistry;
import org.geysermc.connector.network.translators.PacketTranslatorRegistry;
import org.geysermc.connector.network.translators.inventory.EnchantmentInventoryTranslator;
import org.geysermc.connector.network.translators.item.ItemRegistry;
import org.geysermc.connector.network.translators.world.block.BlockTranslator;
import org.geysermc.connector.utils.*;
import org.geysermc.floodgate.util.BedrockData;
import org.geysermc.floodgate.util.EncryptionUtil;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.spec.InvalidKeySpecException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Getter
public class GeyserSession implements CommandSender {

    private final GeyserConnector connector;
    private final UpstreamSession upstream;
    private RemoteServer remoteServer;
    private Client downstream;
    @Setter
    private AuthData authData;
    @Setter
    private BedrockClientData clientData;

    private PlayerEntity playerEntity;
    private PlayerInventory inventory;

    private ChunkCache chunkCache;
    private EntityCache entityCache;
    private InventoryCache inventoryCache;
    private WorldCache worldCache;
    private WindowCache windowCache;
	private Map<Position, PlayerEntity> skullCache = new ConcurrentHashMap<>();
    private final Int2ObjectMap<TeleportCache> teleportMap = new Int2ObjectOpenHashMap<>();

    @Getter
    private final Long2ObjectMap<ClientboundMapItemDataPacket> storedMaps = Long2ObjectMaps.synchronize(new Long2ObjectOpenHashMap<>());

    /**
     * A map of Vector3i positions to Java entity IDs.
     * Used for translating Bedrock block actions to Java entity actions.
     */
    private final Object2LongMap<Vector3i> itemFrameCache = new Object2LongOpenHashMap<>();

    private DataCache<Packet> javaPacketCache;

    @Setter
    private Vector2i lastChunkPosition = null;

    // This is used to store the render distance sent by the Java server so we can use it to update the client accordingly
    private int serverRenderDistance;

    // This is the bedrock client equivalent of the above `serverRenderDistance`
    @Setter
    private int clientRenderDistance;

    private boolean loggedIn;
    private boolean loggingIn;

    @Setter
    private boolean spawned;
    private boolean closed;

    @Setter
    private GameMode gameMode = GameMode.SURVIVAL;

    private final AtomicInteger pendingDimSwitches = new AtomicInteger(0);

    @Setter
    private boolean sneaking;

    @Setter
    private boolean sprinting;

    @Setter
    private boolean jumping;

    @Setter
    private int breakingBlock;

    @Setter
    private Vector3i lastBlockPlacePosition;

    @Setter
    private String lastBlockPlacedId;

    @Setter
    private boolean interacting;

    /**
     * Stores the last position of the block the player interacted with. This can either be a block that the client
     * placed or an existing block the player interacted with (for example, a chest). <br>
     * Initialized as (0, 0, 0) so it is always not-null.
     */
    @Setter
    private Vector3i lastInteractionPosition = Vector3i.ZERO;

    private boolean manyDimPackets = false;
    private ServerRespawnPacket lastDimPacket = null;

    @Setter
    private Entity ridingVehicleEntity;

    @Setter
    private int craftSlot = 0;

    @Setter
    private long lastWindowCloseTime = 0;

    @Setter
    private VillagerTrade[] villagerTrades;
    @Setter
    private long lastInteractedVillagerEid;

    /**
     * Stores the enchantment information the client has received if they are in an enchantment table GUI
     */
    private final EnchantmentInventoryTranslator.EnchantmentSlotData[] enchantmentSlotData = new EnchantmentInventoryTranslator.EnchantmentSlotData[3];

    /**
     * The current attack speed of the player. Used for sending proper cooldown timings.
     */
    @Setter
    private double attackSpeed;
    /**
     * The time of the last hit. Used to gauge how long the cooldown is taking.
     * This is a session variable in order to prevent more scheduled threads than necessary.
     */
    @Setter
    private long lastHitTime;

    /**
     * Store the last time the player interacted. Used to fix a right-click spam bug.
     * See https://github.com/GeyserMC/Geyser/issues/503 for context.
     */
    @Setter
    private long lastInteractionTime;

    private boolean reducedDebugInfo = false;

    @Setter
    private CustomFormWindow settingsForm;

    /**
     * The op permission level set by the server
     */
    @Setter
    private int opPermissionLevel = 0;

    /**
     * If the current player can fly
     */
    @Setter
    private boolean canFly = false;

    /**
     * If the current player is flying
     */
    @Setter
    private boolean flying = false;

    /**
     * If the current player is in noclip
     */
    @Setter
    private boolean noClip = false;

    /**
     * If the current player can not interact with the world
     */
    @Setter
    private boolean worldImmutable = false;

    /**
     * Caches current rain status.
     */
    @Setter
    private boolean raining = false;

    /**
     * Caches current thunder status.
     */
    @Setter
    private boolean thunder = false;

    /**
     * Stores the last text inputted into a sign.
     *
     * Bedrock sends packets every time you update the sign, Java only wants the final packet.
     * Until we determine that the user has finished editing, we save the sign's current status.
     */
    @Setter
    private String lastSignMessage;

    private MinecraftProtocol protocol;

    public GeyserSession(GeyserConnector connector, BedrockServerSession bedrockServerSession) {
        this.connector = connector;
        this.upstream = new UpstreamSession(bedrockServerSession);

        this.chunkCache = new ChunkCache(this);
        this.entityCache = new EntityCache(this);
        this.inventoryCache = new InventoryCache(this);
        this.worldCache = new WorldCache(this);
        this.windowCache = new WindowCache(this);

        this.playerEntity = new PlayerEntity(new GameProfile(UUID.randomUUID(), "unknown"), 1, 1, Vector3f.ZERO, Vector3f.ZERO, Vector3f.ZERO);
        this.inventory = new PlayerInventory();

        this.javaPacketCache = new DataCache<>();

        this.spawned = false;
        this.loggedIn = false;

        this.inventoryCache.getInventories().put(0, inventory);

        EventManager.getInstance().triggerEvent(new SessionConnectEvent(this, "Disconnected by Server")) // TODO: @translate
                .onCancelled(result -> disconnect(result.getEvent().getMessage()));

        bedrockServerSession.addDisconnectHandler(disconnectReason -> {
            EventManager.getInstance().triggerEvent(new SessionDisconnectEvent(this, disconnectReason));

            connector.getLogger().info(LanguageUtils.getLocaleStringLog("geyser.network.disconnect", bedrockServerSession.getAddress().getAddress(), disconnectReason));

            disconnect(disconnectReason.name());
            connector.removePlayer(this);
        });
    }

    public void connect(RemoteServer remoteServer) {
        this.remoteServer = remoteServer;

        PlayerListPacket playerListPacket = new PlayerListPacket();
        playerListPacket.setAction(PlayerListPacket.Action.ADD);
        playerListPacket.getEntries().add(SkinUtils.buildCachedEntry(this, playerEntity.getProfile(), playerEntity.getGeyserId()));
        sendUpstreamPacket(playerListPacket);

        startGame();

        ChunkUtils.sendEmptyChunks(this, playerEntity.getPosition().toInt(), 0, false);

        BiomeDefinitionListPacket biomeDefinitionListPacket = new BiomeDefinitionListPacket();
        biomeDefinitionListPacket.setDefinitions(BiomeTranslator.BIOMES);
        sendUpstreamPacket(biomeDefinitionListPacket);

        AvailableEntityIdentifiersPacket entityPacket = new AvailableEntityIdentifiersPacket();
        entityPacket.setIdentifiers(EntityIdentifierRegistry.ENTITY_IDENTIFIERS);
        sendUpstreamPacket(entityPacket);

        CreativeContentPacket creativePacket = new CreativeContentPacket();
        creativePacket.setContents(ItemRegistry.CREATIVE_ITEMS);
        sendUpstreamPacket(creativePacket);

        UpdateAttributesPacket attributesPacket = new UpdateAttributesPacket();
        attributesPacket.setRuntimeEntityId(getPlayerEntity().getGeyserId());
        List<AttributeData> attributes = new ArrayList<>();
        // Default move speed
        // Bedrock clients move very fast by default until they get an attribute packet correcting the speed
        attributes.add(new AttributeData("minecraft:movement", 0.0f, 1024f, 0.1f, 0.1f));
        attributesPacket.setAttributes(attributes);
        sendUpstreamPacket(attributesPacket);

        GameRulesChangedPacket gamerulePacket = new GameRulesChangedPacket();
        // Only allow the server to send health information
        // Setting this to false allows natural regeneration to work false but doesn't break it being true
        gamerulePacket.getGameRules().add(new GameRuleData<>("naturalregeneration", false));
        // Don't let the client modify the inventory on death
        // Setting this to true allows keep inventory to work if enabled but doesn't break functionality being false
        gamerulePacket.getGameRules().add(new GameRuleData<>("keepinventory", true));
        upstream.sendPacket(gamerulePacket);

        // Spawn the player
        PlayStatusPacket playStatusPacket = new PlayStatusPacket();
        playStatusPacket.setStatus(PlayStatusPacket.Status.PLAYER_SPAWN);
        sendUpstreamPacket(playStatusPacket);
    }

    public void login() {
        if (EventManager.getInstance().triggerEvent(new GeyserLoginEvent(this)).isCancelled()) {
            return;
        }

        if (connector.getAuthType() != AuthType.ONLINE) {
            if (connector.getAuthType() == AuthType.OFFLINE) {
                connector.getLogger().info(LanguageUtils.getLocaleStringLog("geyser.auth.login.offline"));
            } else {
                connector.getLogger().info(LanguageUtils.getLocaleStringLog("geyser.auth.login.floodgate"));
            }
            authenticate(authData.getName());
        }
    }

    public void authenticate(String username) {
        authenticate(username, "");
    }

    public void authenticate(String username, String password) {
        if (loggedIn) {
            connector.getLogger().severe(LanguageUtils.getLocaleStringLog("geyser.auth.already_loggedin", username));
            return;
        }

        loggingIn = true;
        // new thread so clients don't timeout
        new Thread(() -> {
            try {
                if (password != null && !password.isEmpty()) {
                    protocol = new MinecraftProtocol(username, password);
                } else {
                    protocol = new MinecraftProtocol(username);
                }

                boolean floodgate = connector.getAuthType() == AuthType.FLOODGATE;
                final PublicKey publicKey;

                if (floodgate) {
                    PublicKey key = null;
                    try {
                        key = EncryptionUtil.getKeyFromFile(
                                connector.getConfig().getFloodgateKeyPath(),
                                PublicKey.class
                        );
                    } catch (IOException | InvalidKeySpecException | NoSuchAlgorithmException e) {
                        connector.getLogger().error(LanguageUtils.getLocaleStringLog("geyser.auth.floodgate.bad_key"), e);
                    }
                    publicKey = key;
                } else publicKey = null;

                if (publicKey != null) {
                    connector.getLogger().info(LanguageUtils.getLocaleStringLog("geyser.auth.floodgate.loaded_key"));
                }

                downstream = new Client(remoteServer.getAddress(), remoteServer.getPort(), protocol, new TcpSessionFactory());
                downstream.getSession().addListener(new SessionAdapter() {
                    @Override
                    public void packetSending(PacketSendingEvent event) {
                        //todo move this somewhere else
                        if (event.getPacket() instanceof HandshakePacket && floodgate) {
                            String encrypted = "";
                            try {
                                encrypted = EncryptionUtil.encryptBedrockData(publicKey, new BedrockData(
                                        clientData.getGameVersion(),
                                        authData.getName(),
                                        authData.getXboxUUID(),
                                        clientData.getDeviceOS().ordinal(),
                                        clientData.getLanguageCode(),
                                        clientData.getCurrentInputMode().ordinal(),
                                        upstream.getSession().getAddress().getAddress().getHostAddress()
                                ));
                            } catch (Exception e) {
                                connector.getLogger().error(LanguageUtils.getLocaleStringLog("geyser.auth.floodgate.encrypt_fail"), e);
                            }

                            HandshakePacket handshakePacket = event.getPacket();
                            event.setPacket(new HandshakePacket(
                                    handshakePacket.getProtocolVersion(),
                                    handshakePacket.getHostname() + '\0' + BedrockData.FLOODGATE_IDENTIFIER + '\0' + encrypted,
                                    handshakePacket.getPort(),
                                    handshakePacket.getIntent()
                            ));
                        }
                    }

                    @Override
                    public void connected(ConnectedEvent event) {
                        loggingIn = false;
                        loggedIn = true;
                        if (protocol.getProfile() == null) {
                            // Java account is offline
                            disconnect(LanguageUtils.getPlayerLocaleString("geyser.network.remote.invalid_account", clientData.getLanguageCode()));
                            return;
                        }
                        connector.getLogger().info(LanguageUtils.getLocaleStringLog("geyser.network.remote.connect", authData.getName(), protocol.getProfile().getName(), remoteServer.getAddress()));
                        playerEntity.setUuid(protocol.getProfile().getId());
                        playerEntity.setUsername(protocol.getProfile().getName());

                        String locale = clientData.getLanguageCode();

                        // Let the user know there locale may take some time to download
                        // as it has to be extracted from a JAR
                        if (locale.toLowerCase().equals("en_us") && !LocaleUtils.LOCALE_MAPPINGS.containsKey("en_us")) {
                            // This should probably be left hardcoded as it will only show for en_us clients
                            sendMessage("Downloading your locale (en_us) this may take some time");
                        }

                        // Download and load the language for the player
                        LocaleUtils.downloadAndLoadLocale(locale);

                        for (Entity entity : getEntityCache().getEntities().values()) {
                            if (!entity.isValid()) {
                                if (entity instanceof PlayerEntity) {
                                    SkinUtils.requestAndHandleSkinAndCape((PlayerEntity) entity, GeyserSession.this, null);
                                }
                                entity.spawnEntity(GeyserSession.this);
                            }
                        }

                        // Register plugin channels
                        connector.getGeneralThreadPool().schedule(() -> {
                            for (String channel : getConnector().getRegisteredPluginChannels()) {
                                registerPluginChannel(channel);
                            }
                        }, 1, TimeUnit.SECONDS);
                    }

                    @Override
                    public void disconnected(DisconnectedEvent event) {
                        loggingIn = false;
                        loggedIn = false;
                        connector.getLogger().info(LanguageUtils.getLocaleStringLog("geyser.network.remote.disconnect", authData.getName(), remoteServer.getAddress(), event.getReason()));
                        if (event.getCause() != null) {
                            event.getCause().printStackTrace();
                        }

                        upstream.disconnect(MessageUtils.getBedrockMessage(MessageSerializer.fromString(event.getReason())));
                    }

                    @Override
                    public void packetReceived(PacketReceivedEvent event) {
                        if (!closed) {
                            handleDownstreamPacket(event.getPacket());
                        }
                    }

                    @Override
                    public void packetError(PacketErrorEvent event) {
                        connector.getLogger().warning(LanguageUtils.getLocaleStringLog("geyser.network.downstream_error", event.getCause().getMessage()));
                        if (connector.getConfig().isDebugMode())
                            event.getCause().printStackTrace();
                        event.setSuppress(true);
                    }
                });

                downstream.getSession().connect();
                connector.addPlayer(this);
            } catch (InvalidCredentialsException | IllegalArgumentException e) {
                connector.getLogger().info(LanguageUtils.getLocaleStringLog("geyser.auth.login.invalid", username));
                disconnect(LanguageUtils.getPlayerLocaleString("geyser.auth.login.invalid.kick", getClientData().getLanguageCode()));
            } catch (RequestException ex) {
                ex.printStackTrace();
            }
        }).start();
    }

    public void handleDownstreamPacket(Packet packet) {
        //handle consecutive respawn packets
        if (packet.getClass().equals(ServerRespawnPacket.class)) {
            manyDimPackets = lastDimPacket != null;
            lastDimPacket = (ServerRespawnPacket) packet;
            return;
        } else if (lastDimPacket != null) {
            EventResult<DownstreamPacketReceiveEvent<?>> result = EventManager.getInstance().triggerEvent(DownstreamPacketReceiveEvent.of(this, lastDimPacket));
            if (!result.isCancelled()) {
                PacketTranslatorRegistry.JAVA_TRANSLATOR.translate(result.getEvent().getPacket().getClass(), result.getEvent().getPacket(), this);
            }
            lastDimPacket = null;
        }

        // Required, or else Floodgate players break with Bukkit chunk caching
        if (packet instanceof LoginSuccessPacket) {
            GameProfile profile = ((LoginSuccessPacket) packet).getProfile();
            playerEntity.setUsername(profile.getName());
            playerEntity.setUuid(profile.getId());

            // Check if they are not using a linked account
            if (connector.getAuthType() == AuthType.OFFLINE || playerEntity.getUuid().getMostSignificantBits() == 0) {
                SkinUtils.handleBedrockSkin(playerEntity, clientData);
            }
        }
        EventResult<DownstreamPacketReceiveEvent<?>> result = EventManager.getInstance().triggerEvent(DownstreamPacketReceiveEvent.of(this, packet));
        if (!result.isCancelled()) {
            PacketTranslatorRegistry.JAVA_TRANSLATOR.translate(result.getEvent().getPacket().getClass(), result.getEvent().getPacket(), this);
        }
    }

    public void disconnect(String reason) {
        if (!closed) {
            loggedIn = false;
            if (downstream != null && downstream.getSession() != null) {
                downstream.getSession().disconnect(reason);
            }
            if (upstream != null && !upstream.isClosed()) {
                connector.getPlayers().remove(this);
                upstream.disconnect(reason);
            }
        }

        this.chunkCache = null;
        this.entityCache = null;
        this.worldCache = null;
        this.inventoryCache = null;
        this.windowCache = null;

        closed = true;
    }

    public void close() {
        disconnect(LanguageUtils.getPlayerLocaleString("geyser.network.close", getClientData().getLanguageCode()));
    }

    public void setAuthenticationData(AuthData authData) {
        this.authData = authData;
    }

    @Override
    public String getName() {
        return authData.getName();
    }

    @Override
    public void sendMessage(String message) {
        TextPacket textPacket = new TextPacket();
        textPacket.setPlatformChatId("");
        textPacket.setSourceName("");
        textPacket.setXuid("");
        textPacket.setType(TextPacket.Type.CHAT);
        textPacket.setNeedsTranslation(false);
        textPacket.setMessage(message);

        sendUpstreamPacket(textPacket);
    }

    @Override
    public boolean isConsole() {
        return false;
    }

    public void sendForm(FormWindow window, int id) {
        windowCache.showWindow(window, id);
    }

    public void setServerRenderDistance(int serverRenderDistance) {
        serverRenderDistance = GenericMath.ceil(++serverRenderDistance * MathUtils.SQRT_OF_TWO); //square to circle
        if (serverRenderDistance > 32) serverRenderDistance = 32; // <3 u ViaVersion but I don't like crashing clients x)
        this.serverRenderDistance = serverRenderDistance;

        ChunkRadiusUpdatedPacket chunkRadiusUpdatedPacket = new ChunkRadiusUpdatedPacket();
        chunkRadiusUpdatedPacket.setRadius(getRenderDistance());
        upstream.sendPacket(chunkRadiusUpdatedPacket);
    }

    /**
     * This returns the smallest render distance between the server or client
     *
     * @return The render distance as an int
     */
    public int getRenderDistance() {
        return Math.min(clientRenderDistance, serverRenderDistance);
    }

    public InetSocketAddress getSocketAddress() {
        return this.upstream.getAddress();
    }

    public void sendForm(FormWindow window) {
        windowCache.showWindow(window);
    }

    private void startGame() {
        StartGamePacket startGamePacket = new StartGamePacket();
        startGamePacket.setUniqueEntityId(playerEntity.getGeyserId());
        startGamePacket.setRuntimeEntityId(playerEntity.getGeyserId());
        startGamePacket.setPlayerGameType(GameType.SURVIVAL);
        startGamePacket.setPlayerPosition(Vector3f.from(0, 69, 0));
        startGamePacket.setRotation(Vector2f.from(1, 1));

        startGamePacket.setSeed(-1);
        startGamePacket.setDimensionId(DimensionUtils.javaToBedrock(playerEntity.getDimension()));
        startGamePacket.setGeneratorId(1);
        startGamePacket.setLevelGameType(GameType.SURVIVAL);
        startGamePacket.setDifficulty(1);
        startGamePacket.setDefaultSpawn(Vector3i.ZERO);
        startGamePacket.setAchievementsDisabled(true);
        startGamePacket.setCurrentTick(-1);
        startGamePacket.setEduEditionOffers(0);
        startGamePacket.setEduFeaturesEnabled(false);
        startGamePacket.setRainLevel(0);
        startGamePacket.setLightningLevel(0);
        startGamePacket.setMultiplayerGame(true);
        startGamePacket.setBroadcastingToLan(true);
        startGamePacket.getGamerules().add(new GameRuleData<>("showcoordinates", true));
        startGamePacket.setPlatformBroadcastMode(GamePublishSetting.PUBLIC);
        startGamePacket.setXblBroadcastMode(GamePublishSetting.PUBLIC);
        startGamePacket.setCommandsEnabled(true);
        startGamePacket.setTexturePacksRequired(false);
        startGamePacket.setBonusChestEnabled(false);
        startGamePacket.setStartingWithMap(false);
        startGamePacket.setTrustingPlayers(true);
        startGamePacket.setDefaultPlayerPermission(PlayerPermission.MEMBER);
        startGamePacket.setServerChunkTickRange(4);
        startGamePacket.setBehaviorPackLocked(false);
        startGamePacket.setResourcePackLocked(false);
        startGamePacket.setFromLockedWorldTemplate(false);
        startGamePacket.setUsingMsaGamertagsOnly(false);
        startGamePacket.setFromWorldTemplate(false);
        startGamePacket.setWorldTemplateOptionLocked(false);

        String serverName = connector.getConfig().getBedrock().getServerName();
        startGamePacket.setLevelId(serverName);
        startGamePacket.setLevelName(serverName);

        startGamePacket.setPremiumWorldTemplateId("00000000-0000-0000-0000-000000000000");
        // startGamePacket.setCurrentTick(0);
        startGamePacket.setEnchantmentSeed(0);
        startGamePacket.setMultiplayerCorrelationId("");
        startGamePacket.setBlockPalette(BlockTranslator.BLOCKS);
        startGamePacket.setItemEntries(ItemRegistry.ITEMS);
        startGamePacket.setVanillaVersion("*");
        startGamePacket.setAuthoritativeMovementMode(AuthoritativeMovementMode.CLIENT);
        sendUpstreamPacket(startGamePacket);
    }

    public void addTeleport(TeleportCache teleportCache) {
        teleportMap.put(teleportCache.getTeleportConfirmId(), teleportCache);
    }

    public boolean confirmTeleport(Vector3d position) {
        int teleportID = -1;

        for (Int2ObjectMap.Entry<TeleportCache> entry : teleportMap.int2ObjectEntrySet()) {
            if (entry.getValue().canConfirm(position)) {
                if (entry.getValue().getTeleportConfirmId() > teleportID) {
                    teleportID = entry.getValue().getTeleportConfirmId();
                }
            }
        }

        ObjectIterator<Int2ObjectMap.Entry<TeleportCache>> it = teleportMap.int2ObjectEntrySet().iterator();

        if (teleportID != -1) {
            // Confirm the current teleport and any earlier ones
            // TODO: Don't assume that IDs go up over time
            while (it.hasNext()) {
                Int2ObjectMap.Entry<TeleportCache> entry = it.next();
                int nextID = entry.getValue().getTeleportConfirmId();
                if (nextID <= teleportID) {
                    ClientTeleportConfirmPacket teleportConfirmPacket = new ClientTeleportConfirmPacket(nextID);
                    sendDownstreamPacket(teleportConfirmPacket);
                    it.remove();
                    connector.getLogger().debug("Confirmed teleport " + nextID);
                }
            }
        }

        if (teleportMap.size() > 0) {
            int resendID = -1;
            for (Int2ObjectMap.Entry<TeleportCache> entry : teleportMap.int2ObjectEntrySet()) {
                TeleportCache teleport = entry.getValue();
                teleport.incrementUnconfirmedFor();
                if (teleport.shouldResend()) {
                    if (teleport.getTeleportConfirmId() >= resendID) {
                        resendID = teleport.getTeleportConfirmId();
                    }
                }
            }

            if (resendID != -1) {
                connector.getLogger().debug("Resending teleport " + resendID);
                TeleportCache teleport = teleportMap.get(resendID);
                getPlayerEntity().moveAbsolute(this, Vector3f.from(teleport.getX(), teleport.getY(), teleport.getZ()), (float) teleport.getYaw(), (float) teleport.getPitch(), true, true);
            }
        }

        return true;
    }

    /**
     * Queue a packet to be sent to player.
     *
     * @param packet the bedrock packet from the NukkitX protocol lib
     */
    public void sendUpstreamPacket(BedrockPacket packet) {
        EventManager.getInstance().triggerEvent(UpstreamPacketSendEvent.of(this, packet))
                .onNotCancelled(result -> {
                    if (upstream != null) {
                        upstream.sendPacket(result.getEvent().getPacket());
                    } else {
                        connector.getLogger().debug("Tried to send upstream packet " + result.getEvent().getPacket().getClass().getSimpleName() + " but the session was null");
                    }
                });
    }

    /**
     * Inject a packet as if it was received by the upstream client
     * @param packet the bedrock packet to be injected
     * @return true if handled
     */
    @SuppressWarnings("unused")
    public boolean receiveUpstreamPacket(BedrockPacket packet) {
        return packet.handle(getUpstream().getSession().getPacketHandler());
    }

    /**
     * Inject a packet as if it was received by the downstream server
     * @param packet the java packet to be injected
     */
    @SuppressWarnings("unused")
    public void receiveDownstreamPacket(Packet packet) {
        handleDownstreamPacket(packet);
    }

    /**
     * Send a packet immediately to the player.
     *
     * @param packet the bedrock packet from the NukkitX protocol lib
     */
    public void sendUpstreamPacketImmediately(BedrockPacket packet) {
        EventManager.getInstance().triggerEvent(UpstreamPacketSendEvent.of(this, packet))
                .onNotCancelled(result -> {
                    if (upstream != null) {
                        upstream.sendPacketImmediately(result.getEvent().getPacket());
                    } else {
                        connector.getLogger().debug("Tried to send upstream packet " + result.getEvent().getPacket().getClass().getSimpleName() + " immediately but the session was null");
                    }
                });
    }

    /**
     * Send a packet to the remote server.
     *
     * @param packet the java edition packet from MCProtocolLib
     */
    public void sendDownstreamPacket(Packet packet) {
        EventManager.getInstance().triggerEvent(DownstreamPacketSendEvent.of(this, packet))
                .onNotCancelled(result -> {
                    if (downstream != null && downstream.getSession() != null && protocol.getSubProtocol().equals(SubProtocol.GAME)) {
                        downstream.getSession().send(result.getEvent().getPacket());
                    } else {
                        connector.getLogger().debug("Tried to send downstream packet " + result.getEvent().getPacket().getClass().getSimpleName() + " before connected to the server");
                    }
                });
    }

    /**
     * Update the cached value for the reduced debug info gamerule.
     * This also toggles the coordinates display
     *
     * @param value The new value for reducedDebugInfo
     */
    public void setReducedDebugInfo(boolean value) {
        worldCache.setShowCoordinates(!value);
        reducedDebugInfo = value;
    }

    /**
     * Send a gamerule value to the client
     *
     * @param gameRule The gamerule to send
     * @param value The value of the gamerule
     */
    public void sendGameRule(String gameRule, Object value) {
        GameRulesChangedPacket gameRulesChangedPacket = new GameRulesChangedPacket();
        gameRulesChangedPacket.getGameRules().add(new GameRuleData<>(gameRule, value));
        upstream.sendPacket(gameRulesChangedPacket);
    }

    /**
     * Checks if the given session's player has a permission
     *
     * @param permission The permission node to check
     * @return true if the player has the requested permission, false if not
     */
    public Boolean hasPermission(String permission) {
        return connector.getWorldManager().hasPermission(this, permission);
    }

    /**
     * Send an AdventureSettingsPacket to the client with the latest flags
     */
    public void sendAdventureSettings() {
        AdventureSettingsPacket adventureSettingsPacket = new AdventureSettingsPacket();
        adventureSettingsPacket.setUniqueEntityId(playerEntity.getGeyserId());
        // Set command permission if OP permission level is high enough
        // This allows mobile players access to a GUI for doing commands. The commands there do not change above OPERATOR
        // and all commands there are accessible with OP permission level 2
        adventureSettingsPacket.setCommandPermission(opPermissionLevel >= 2 ? CommandPermission.OPERATOR : CommandPermission.NORMAL);
        // Required to make command blocks destroyable
        adventureSettingsPacket.setPlayerPermission(opPermissionLevel >= 2 ? PlayerPermission.OPERATOR : PlayerPermission.MEMBER);

        Set<AdventureSetting> flags = new HashSet<>();
        if (canFly) {
            flags.add(AdventureSetting.MAY_FLY);
        }

        if (flying) {
            flags.add(AdventureSetting.FLYING);
        }

        if (worldImmutable) {
            flags.add(AdventureSetting.WORLD_IMMUTABLE);
        }

        if (noClip) {
            flags.add(AdventureSetting.NO_CLIP);
        }

        flags.add(AdventureSetting.AUTO_JUMP);

        adventureSettingsPacket.getSettings().addAll(flags);
        sendUpstreamPacket(adventureSettingsPacket);
    }

    /**
     * Send message on a Plugin Channel - https://www.spigotmc.org/wiki/bukkit-bungee-plugin-messaging-channel
     *
     * @param channel channel to send on
     * @param data payload
     */
    public void sendPluginMessage(String channel, byte[] data) {
        sendDownstreamPacket(new ClientPluginMessagePacket(channel, data));
    }

    /**
     * Register a Plugin Channel
     *
     * @param channel Channel to register
     */
    public void registerPluginChannel(String channel) {
        sendDownstreamPacket(new ClientPluginMessagePacket("minecraft:register", channel.getBytes()));
    }

    /**
     * Unregister a Plugin Channel
     *
     * @param channel Channel to unregister
     */
    public void unregisterPluginChannel(String channel) {
        sendDownstreamPacket(new ClientPluginMessagePacket("minecraft:unregister", channel.getBytes()));
    }
}
