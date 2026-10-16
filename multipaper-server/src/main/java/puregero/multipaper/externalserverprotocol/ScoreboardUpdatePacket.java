package puregero.multipaper.externalserverprotocol;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.ServerScoreboard;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Score;
import net.minecraft.world.scores.Team;
import net.minecraft.world.scores.criteria.ObjectiveCriteria;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import puregero.multipaper.ExternalServerConnection;
import puregero.multipaper.MultiPaper;
import puregero.multipaper.PacketSerialization;

import javax.annotation.Nullable;

public class ScoreboardUpdatePacket extends ExternalServerPacket {

    private static final Logger LOGGER = LogManager.getLogger(ScoreboardUpdatePacket.class.getSimpleName());
    public static boolean updating = false;

    @Nullable
    private final String scoreboardId;
    @Nullable
    private final String criteria;
    private final Packet<?> packet;

    public ScoreboardUpdatePacket(@Nullable String scoreboard, Packet<?> packet) {
        this(scoreboard, null, packet);
    }

    public ScoreboardUpdatePacket(@Nullable String scoreboard, @Nullable ObjectiveCriteria criteria, Packet<?> packet) {
        this.scoreboardId = scoreboard;
        this.criteria = criteria == null ? null : criteria.getName();
        this.packet = packet;
    }

    public ScoreboardUpdatePacket(FriendlyByteBuf in) {
        String scoreboard = in.readUtf();
        this.scoreboardId = scoreboard.isEmpty() ? null : scoreboard;

        String criteria = in.readUtf();
        this.criteria = criteria.isEmpty() ? null : criteria;

        packet = PacketSerialization.readClientbound(in.readByteArray());
    }

    @Override
    public void write(FriendlyByteBuf out) {
        out.writeUtf(scoreboardId == null ? "" : scoreboardId);

        out.writeUtf(criteria == null ? "" : criteria);

        out.writeByteArray(PacketSerialization.writeClientbound(packet));
    }

    @Override
    public void handle(ExternalServerConnection connection) {
        MultiPaper.runSync(() -> {
            if (scoreboardId != null) {
                throw new UnsupportedOperationException("Modifying scoreboard '" + scoreboardId + "' is not supported!");
            }

            updating = true;

            ServerScoreboard scoreboard = MinecraftServer.getServer().getScoreboard();

            if (packet instanceof ClientboundSetPlayerTeamPacket setPlayerTeamPacket) {
                handle(scoreboard, setPlayerTeamPacket);
            } else if (packet instanceof ClientboundSetScorePacket setScorePacket) {
                handle(scoreboard, setScorePacket);
            } else if (packet instanceof ClientboundSetObjectivePacket setObjectivePacket) {
                handle(scoreboard, criteria, setObjectivePacket);
            } else if (packet instanceof ClientboundSetDisplayObjectivePacket setDisplayObjectivePacket) {
                handle(scoreboard, setDisplayObjectivePacket);
            } else {
                LOGGER.warn("Unhandled scoreboard update packet " + packet);
            }

            updating = false;
        });
    }

    private void handle(ServerScoreboard scoreboard, ClientboundSetPlayerTeamPacket setPlayerTeamPacket) {
        PlayerTeam team = scoreboard.getPlayerTeam(setPlayerTeamPacket.getName());

        if (setPlayerTeamPacket.getTeamAction() == ClientboundSetPlayerTeamPacket.Action.ADD && team == null) {
            team = scoreboard.addPlayerTeam(setPlayerTeamPacket.getName());
        } else if (setPlayerTeamPacket.getTeamAction() == ClientboundSetPlayerTeamPacket.Action.REMOVE) {
            scoreboard.removePlayerTeam(team);
        }

        try {
            if (setPlayerTeamPacket.getPlayerAction() == ClientboundSetPlayerTeamPacket.Action.ADD) {
                for (String player : setPlayerTeamPacket.getPlayers()) {
                    scoreboard.addPlayerToTeam(player, team);
                }
            } else if (setPlayerTeamPacket.getPlayerAction() == ClientboundSetPlayerTeamPacket.Action.REMOVE) {
                for (String player : setPlayerTeamPacket.getPlayers()) {
                    scoreboard.removePlayerFromTeam(player, team);
                }
            }
        } catch (Exception e) {
            // These can be usually safely ignored, just an innocent race condition
        }

        if (setPlayerTeamPacket.getParameters().isPresent()) {
            ClientboundSetPlayerTeamPacket.Parameters parameters = setPlayerTeamPacket.getParameters().get();
            team.setDisplayName(parameters.displayName());
            team.unpackOptions(team.packOptions());
            team.setNameTagVisibility(parameters.nameTagVisibility());
            team.setCollisionRule(parameters.collisionRule());
            team.setColor(parameters.color());
            team.setPlayerPrefix(parameters.playerPrefix());
            team.setPlayerSuffix(parameters.playerSuffix());
        }
    }

    private void handle(ServerScoreboard scoreboard, ClientboundSetScorePacket setScorePacket) {
        Objective objective = scoreboard.getObjective(setScorePacket.objectiveName());

        if (objective != null) {
            scoreboard.getOrCreatePlayerScore(net.minecraft.world.scores.ScoreHolder.forNameOnly(setScorePacket.owner()), objective).set(setScorePacket.score());
        }
    }

    private void handle(ServerScoreboard scoreboard, String criteria, ClientboundSetObjectivePacket setObjectivePacket) {
        Objective objective = scoreboard.getObjective(setObjectivePacket.getObjectiveName());
        ObjectiveCriteria objectiveCriteria = criteria == null ? null : ObjectiveCriteria.byName(criteria).get();

        if (setObjectivePacket.getMethod() == ClientboundSetObjectivePacket.METHOD_ADD && objective == null) {
            objective = scoreboard.addObjective(setObjectivePacket.getObjectiveName(), objectiveCriteria, setObjectivePacket.getDisplayName(), setObjectivePacket.getRenderType(), false, setObjectivePacket.getNumberFormat().orElse(null));
        } else if (setObjectivePacket.getMethod() == ClientboundSetObjectivePacket.METHOD_REMOVE) {
            scoreboard.removeObjective(objective);
        } if (setObjectivePacket.getMethod() == ClientboundSetObjectivePacket.METHOD_CHANGE || setObjectivePacket.getMethod() == ClientboundSetObjectivePacket.METHOD_ADD) {
            objective.setDisplayName(setObjectivePacket.getDisplayName());
            objective.setRenderType(setObjectivePacket.getRenderType());
        }
    }

    private void handle(ServerScoreboard scoreboard, ClientboundSetDisplayObjectivePacket setDisplayObjectivePacket) {
        Objective objective = scoreboard.getObjective(setDisplayObjectivePacket.getObjectiveName());

        scoreboard.setDisplayObjective(setDisplayObjectivePacket.getSlot(), objective);
    }
}
