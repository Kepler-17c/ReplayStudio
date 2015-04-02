package de.johni0702.replaystudio.filter;

import com.google.gson.JsonObject;
import de.johni0702.replaystudio.PacketData;
import de.johni0702.replaystudio.Studio;
import de.johni0702.replaystudio.stream.PacketStream;
import de.johni0702.replaystudio.util.Location;
import de.johni0702.replaystudio.util.PacketUtils;
import org.spacehq.mc.protocol.data.game.values.entity.player.GameMode;
import org.spacehq.mc.protocol.data.game.values.scoreboard.NameTagVisibility;
import org.spacehq.mc.protocol.data.game.values.scoreboard.TeamAction;
import org.spacehq.mc.protocol.data.game.values.scoreboard.TeamColor;
import org.spacehq.mc.protocol.data.game.values.setting.Difficulty;
import org.spacehq.mc.protocol.data.game.values.world.WorldType;
import org.spacehq.mc.protocol.data.game.values.world.notify.ClientNotification;
import org.spacehq.mc.protocol.packet.ingame.server.ServerDifficultyPacket;
import org.spacehq.mc.protocol.packet.ingame.server.ServerJoinGamePacket;
import org.spacehq.mc.protocol.packet.ingame.server.ServerRespawnPacket;
import org.spacehq.mc.protocol.packet.ingame.server.entity.*;
import org.spacehq.mc.protocol.packet.ingame.server.entity.player.ServerPlayerAbilitiesPacket;
import org.spacehq.mc.protocol.packet.ingame.server.entity.player.ServerPlayerPositionRotationPacket;
import org.spacehq.mc.protocol.packet.ingame.server.entity.player.ServerSetExperiencePacket;
import org.spacehq.mc.protocol.packet.ingame.server.scoreboard.ServerTeamPacket;
import org.spacehq.mc.protocol.packet.ingame.server.window.*;
import org.spacehq.mc.protocol.packet.ingame.server.world.*;
import org.spacehq.packetlib.packet.Packet;

import java.util.*;

import static de.johni0702.replaystudio.io.WrappedPacket.instanceOf;
import static de.johni0702.replaystudio.util.Java8.Map8.getOrCreate;
import static de.johni0702.replaystudio.util.Utils.within;

public class SquashFilter extends StreamFilterBase {

    private static final long POS_MIN = Byte.MIN_VALUE;
    private static final long POS_MAX = Byte.MAX_VALUE;

    private static class Team {
        private static enum Status {
            CREATED, UPDATED, REMOVED
        }

        private final Status status;

        private String name;
        private String displayName;
        private String prefix;
        private String suffix;
        private boolean friendlyFire;
        private boolean seeingFriendlyInvisibles;
        private NameTagVisibility nameTagVisibility;
        private TeamColor color;
        private final Set<String> added = new HashSet<>();
        private final Set<String> removed = new HashSet<>();

        public Team(Status status) {
            this.status = status;
        }
    }

    private static class Entity {
        private List<PacketData> packets = new ArrayList<>();
        private long lastTimestamp = 0;
        private Location loc = null;
        private long dx = 0;
        private long dy = 0;
        private long dz = 0;
        private Float yaw = null;
        private Float pitch = null;
        private boolean onGround = false;
    }

    private final List<PacketData> unhandled = new ArrayList<>();
    private final Map<Integer, Entity> entities = new HashMap<>();
    private final Map<String, Team> teams = new HashMap<>();
    private final Map<Integer, PacketData> mainInventoryChanges = new HashMap<>();

    private final List<PacketData> currentWorld = new ArrayList<>();
    private final List<PacketData> currentWindow = new ArrayList<>();
    private final List<PacketData> closeWindows = new ArrayList<>();

    private long lastTimestamp;

    private GameMode gameMode = null;
    private Integer dimension = null;
    private Difficulty difficulty = null;
    private WorldType worldType = null;
    private Boolean reducedDebugInfo = null;
    private PacketData joinGame;
    private PacketData respawn;
    private PacketData mainInventory;
    private ServerSetExperiencePacket experience = null;
    private ServerPlayerAbilitiesPacket abilities = null;

    @Override
    public void onStart(PacketStream stream) {

    }

    @Override
    public boolean onPacket(PacketStream stream, PacketData data) {
        Packet packet = data.getPacket();
        lastTimestamp = data.getTime();

        // Entities
        Integer entityId = PacketUtils.getEntityId(packet);
        if (entityId != null) { // Some entity is associated with this packet
            if (entityId == -1) { // Multiple entities in fact
                for (int id : PacketUtils.getEntityIds(packet)) {
                    if (packet instanceof ServerDestroyEntitiesPacket) {
                        entities.remove(id);
                    } else {
                        getOrCreate(entities, id, Entity::new).packets.add(data);
                    }
                }
            } else { // Only one entity
                Entity entity = getOrCreate(entities, entityId, Entity::new);
                if (packet instanceof ServerEntityMovementPacket) {
                    ServerEntityMovementPacket p = (ServerEntityMovementPacket) packet;
                    double mx = p.getMovementX();
                    double my = p.getMovementY();
                    double mz = p.getMovementZ();
                    entity.onGround = p.isOnGround();

                    if (p instanceof ServerEntityPositionPacket || p instanceof ServerEntityPositionRotationPacket) {
                        entity.dx += mx * 32;
                        entity.dy += my * 32;
                        entity.dz += mz * 32;
                    }
                    if (p instanceof ServerEntityRotationPacket || p instanceof ServerEntityPositionRotationPacket) {
                        entity.yaw = p.getYaw();
                        entity.pitch = p.getPitch();
                    }
                } else if (packet instanceof ServerEntityTeleportPacket) {
                    ServerEntityTeleportPacket p = (ServerEntityTeleportPacket) packet;
                    entity.loc = Location.from(p);
                    entity.dx = entity.dy = entity.dz = 0;
                    entity.yaw = entity.pitch = null;
                    entity.onGround = p.isOnGround();
                } else {
                    entity.packets.add(data);
                }
                entity.lastTimestamp = lastTimestamp;
            }
            return false;
        }

        // World
        if (packet instanceof ServerNotifyClientPacket) {
            ServerNotifyClientPacket p = (ServerNotifyClientPacket) packet;
            if (p.getNotification() == ClientNotification.CHANGE_GAMEMODE) {
                gameMode = (GameMode) p.getValue();
                return false;
            }
        }

        if (packet instanceof ServerSetExperiencePacket) {
            experience = (ServerSetExperiencePacket) packet;
            return false;
        }

        if (packet instanceof ServerPlayerAbilitiesPacket) {
            abilities = (ServerPlayerAbilitiesPacket) packet;
            return false;
        }

        if (packet instanceof ServerDifficultyPacket) {
            difficulty = ((ServerDifficultyPacket) packet).getDifficulty();
            return false;
        }

        if (packet instanceof ServerJoinGamePacket) {
            ServerJoinGamePacket p = (ServerJoinGamePacket) packet;
            gameMode = p.getGameMode();
            dimension = p.getDimension();
            difficulty = p.getDifficulty();
            worldType = p.getWorldType();
            reducedDebugInfo = p.getReducedDebugInfo();
            joinGame = data;
            return false;
        }

        if (packet instanceof ServerRespawnPacket) {
            ServerRespawnPacket p = (ServerRespawnPacket) packet;
            dimension = p.getDimension();
            difficulty = p.getDifficulty();
            worldType = p.getWorldType();
            gameMode = p.getGameMode();
            currentWorld.clear();
            currentWindow.clear();
            entities.clear();
            respawn = data;
            return false;
        }

        if (instanceOf(packet, ServerPlayerPositionRotationPacket.class)
                || instanceOf(packet, ServerRespawnPacket.class)
                || instanceOf(packet, ServerBlockBreakAnimPacket.class)
                || instanceOf(packet, ServerBlockChangePacket.class)
                || instanceOf(packet, ServerBlockValuePacket.class)
                || instanceOf(packet, ServerChunkDataPacket.class)
                || instanceOf(packet, ServerExplosionPacket.class)
                || instanceOf(packet, ServerMapDataPacket.class)
                || instanceOf(packet, ServerMultiBlockChangePacket.class)
                || instanceOf(packet, ServerMultiChunkDataPacket.class)
                || instanceOf(packet, ServerOpenTileEntityEditorPacket.class)
                || instanceOf(packet, ServerPlayEffectPacket.class)
                || instanceOf(packet, ServerPlaySoundPacket.class)
                || instanceOf(packet, ServerSpawnParticlePacket.class)
                || instanceOf(packet, ServerSpawnPositionPacket.class)
                || instanceOf(packet, ServerUpdateSignPacket.class)
                || instanceOf(packet, ServerUpdateTileEntityPacket.class)
                || instanceOf(packet, ServerUpdateTimePacket.class)
                || instanceOf(packet, ServerWorldBorderPacket.class)) {
            currentWorld.add(data);
            return false;
        }

        // Windows
        if (packet instanceof ServerCloseWindowPacket) {
            currentWindow.clear();
            closeWindows.add(data);
            return false;
        }

        if (instanceOf(packet, ServerConfirmTransactionPacket.class)) {
            return false; // This packet isn't of any use in replays
        }

        if (instanceOf(packet, ServerOpenWindowPacket.class)
                || instanceOf(packet, ServerWindowPropertyPacket.class)) {
            currentWindow.add(data);
            return false;
        }

        if (packet instanceof ServerWindowItemsPacket) {
            ServerWindowItemsPacket p = (ServerWindowItemsPacket) packet;
            if (p.getWindowId() == 0) {
                mainInventory = data;
            } else {
                currentWindow.add(data);
            }
            return false;
        }

        if (packet instanceof ServerSetSlotPacket) {
            ServerSetSlotPacket p = (ServerSetSlotPacket) packet;
            if (p.getWindowId() == 0) {
                mainInventoryChanges.put(p.getSlot(), data);
            } else {
                currentWindow.add(data);
            }
            return false;
        }

        // Teams
        if (packet instanceof ServerTeamPacket) {
            ServerTeamPacket p = (ServerTeamPacket) packet;
            Team team = teams.get(p.getTeamName());
            if (team == null) {
                Team.Status status;
                if (p.getAction() == TeamAction.CREATE) {
                    status = Team.Status.CREATED;
                } else if (p.getAction() == TeamAction.REMOVE) {
                    status = Team.Status.REMOVED;
                } else {
                    status = Team.Status.UPDATED;
                }
                team = new Team(status);
                team.name = p.getTeamName();
                teams.put(team.name, team);
            }
            TeamAction action = p.getAction();
            if (action == TeamAction.REMOVE && team.status == Team.Status.CREATED) {
                teams.remove(team.name);
            }
            if (action == TeamAction.CREATE || action == TeamAction.UPDATE) {
                team.displayName = p.getDisplayName();
                team.prefix = p.getPrefix();
                team.suffix = p.getSuffix();
                team.friendlyFire = p.getFriendlyFire();
                team.seeingFriendlyInvisibles = p.getSeeFriendlyInvisibles();
                team.nameTagVisibility = p.getNameTagVisibility();
                team.color = p.getColor();
            }
            if (action == TeamAction.ADD_PLAYER || action == TeamAction.CREATE) {
                for (String player : p.getPlayers()) {
                    if (!team.removed.remove(player)) {
                        team.added.add(player);
                    }
                }
            }
            if (action == TeamAction.REMOVE_PLAYER) {
                for (String player : p.getPlayers()) {
                    if (!team.added.remove(player)) {
                        team.removed.add(player);
                    }
                }
            }
            return false;
        }

        unhandled.add(data);
        return false;
    }

    @Override
    public void onEnd(PacketStream stream, long timestamp) {
        List<PacketData> result = new ArrayList<>();

        result.addAll(unhandled);
        result.addAll(currentWorld);
        result.addAll(currentWindow);
        result.addAll(closeWindows);
        result.addAll(mainInventoryChanges.values());

        if (mainInventory != null) {
            result.add(mainInventory);
        }

        if (joinGame != null) {
            ServerJoinGamePacket org = (ServerJoinGamePacket) joinGame.getPacket();
            Packet packet = new ServerJoinGamePacket(org.getEntityId(), org.getHardcore(), gameMode, dimension,
                    difficulty, org.getMaxPlayers(), worldType, reducedDebugInfo);
            result.add(new PacketData(joinGame.getTime(), packet));
        } else if (respawn != null) {
            Packet packet = new ServerRespawnPacket(dimension, difficulty, gameMode, worldType);
            result.add(new PacketData(respawn.getTime(), packet));
        } else {
            if (difficulty != null) {
                result.add(new PacketData(lastTimestamp, new ServerDifficultyPacket(difficulty)));
            }
            if (gameMode != null) {
                Packet packet = new ServerNotifyClientPacket(ClientNotification.CHANGE_GAMEMODE, gameMode);
                result.add(new PacketData(lastTimestamp, packet));
            }
        }

        if (experience != null) {
            result.add(new PacketData(lastTimestamp, experience));
        }
        if (abilities != null) {
            result.add(new PacketData(lastTimestamp, abilities));
        }

        for (Map.Entry<Integer, Entity> e : entities.entrySet()) {
            Entity entity = e.getValue();

            FOR_PACKETS:
            for (PacketData data : entity.packets) {
                Packet packet = data.getPacket();
                Integer id = PacketUtils.getEntityId(packet);
                if (id == -1) { // Multiple entities
                    List<Integer> allIds = PacketUtils.getEntityIds(packet);
                    for (int i : allIds) {
                        if (!entities.containsKey(i)) { // Other entity doesn't exist
                            continue FOR_PACKETS;
                        }
                    }
                }
                result.add(data);
            }

            if (entity.loc != null) {
                result.add(new PacketData(entity.lastTimestamp, entity.loc.toServerEntityTeleportPacket(e.getKey(), entity.onGround)));
            }
            while (entity.dx != 0 && entity.dy != 0 && entity.dz != 0) {
                long mx = within(entity.dx, POS_MIN, POS_MAX);
                long my = within(entity.dy, POS_MIN, POS_MAX);
                long mz = within(entity.dz, POS_MIN, POS_MAX);
                entity.dx -= mx;
                entity.dy -= my;
                entity.dz -= mz;
                ServerEntityPositionPacket p = new ServerEntityPositionPacket(e.getKey(), mx / 32d, my / 32d, mz / 32d, entity.onGround);
                result.add(new PacketData(entity.lastTimestamp, p));
            }
            if (entity.yaw != null && entity.pitch != null) {
                ServerEntityRotationPacket p = new ServerEntityRotationPacket(e.getKey(), entity.yaw, entity.pitch, entity.onGround);
                result.add(new PacketData(entity.lastTimestamp, p));
            }
        }

        Collections.sort(result, (e1, e2) -> Long.compare(e1.getTime(), e2.getTime()));
        for (PacketData data : result) {
            add(stream, timestamp, data.getPacket());
        }

        for (Team team : teams.values()) {
            String[] added = team.added.toArray(new String[team.added.size()]);
            String[] removed = team.added.toArray(new String[team.removed.size()]);
            if (team.status == Team.Status.CREATED) {
                add(stream, timestamp, new ServerTeamPacket(team.name, team.displayName, team.prefix, team.suffix,
                        team.friendlyFire, team.seeingFriendlyInvisibles, team.nameTagVisibility, team.color, added));
            } else if (team.status == Team.Status.UPDATED) {
                if (team.color != null) {
                    add(stream, timestamp, new ServerTeamPacket(team.name, team.displayName, team.prefix, team.suffix,
                            team.friendlyFire, team.seeingFriendlyInvisibles, team.nameTagVisibility, team.color));
                }
                if (added.length > 0) {
                    add(stream, timestamp, new ServerTeamPacket(team.name, TeamAction.ADD_PLAYER, added));
                }
                if (removed.length > 0) {
                    add(stream, timestamp, new ServerTeamPacket(team.name, TeamAction.REMOVE_PLAYER, removed));
                }
            } else if (team.status == Team.Status.REMOVED) {
                add(stream, timestamp, new ServerTeamPacket(team.name));
            }
        }
    }

    @Override
    public String getName() {
        return "squash";
    }

    @Override
    public void init(Studio studio, JsonObject config) {
        PacketUtils.registerAllEntityRelated(studio);

        studio.setParsing(ServerNotifyClientPacket.class, true);
        studio.setParsing(ServerSetExperiencePacket.class, true);
        studio.setParsing(ServerPlayerAbilitiesPacket.class, true);
        studio.setParsing(ServerDifficultyPacket.class, true);
        studio.setParsing(ServerJoinGamePacket.class, true);
        studio.setParsing(ServerRespawnPacket.class, true);
        studio.setParsing(ServerTeamPacket.class, true);
        studio.setParsing(ServerCloseWindowPacket.class, true);
        studio.setParsing(ServerWindowItemsPacket.class, true);
        studio.setParsing(ServerSetSlotPacket.class, true);
    }

    private void add(PacketStream stream, long timestamp, Packet packet) {
        stream.insert(new PacketData(timestamp, packet));
    }

}
