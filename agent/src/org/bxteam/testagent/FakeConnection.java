package org.bxteam.testagent;

import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.ReferenceCountUtil;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayDeque;
import java.util.Queue;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.ClientboundKeepAlivePacket;
import net.minecraft.network.protocol.common.ServerboundKeepAlivePacket;
import net.minecraft.network.protocol.game.ClientboundChunkBatchFinishedPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket;
import net.minecraft.network.protocol.game.ServerboundChunkBatchReceivedPacket;
import net.minecraft.server.level.ServerPlayer;

/**
 * A server-side connection with no client: outgoing packets are dropped, and the few replies the server
 * waits for (keepalive, chunk batch ack, teleport ack) are queued and delivered on the next tick, like a client would.
 */
final class FakeConnection extends Connection {
    // a Packet, or an Integer teleport id whose ack is built when it is delivered
    private final Queue<Object> replies = new ArrayDeque<>();
    long packetsDropped;
    ServerPlayer player;

    FakeConnection() {
        super(PacketFlow.SERVERBOUND);
        this.channel = new EmbeddedChannel(new ChannelOutboundHandlerAdapter() {
            @Override
            public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
                ReferenceCountUtil.release(msg);
                promise.setSuccess();
            }
        });
        this.address = new InetSocketAddress(InetAddress.getLoopbackAddress(), 0);
    }

    @Override
    public void send(Packet<?> packet, ChannelFutureListener listener, boolean flush) {
        switch (packet) {
            case ClientboundKeepAlivePacket keepAlive -> this.replies.add(new ServerboundKeepAlivePacket(keepAlive.getId()));
            // the chunk sender stops after a few unacknowledged batches
            case ClientboundChunkBatchFinishedPacket ignored -> this.replies.add(new ServerboundChunkBatchReceivedPacket(64.0F));
            // 26.3 applies the position carried by the ack, so it must be the player's position when the ack arrives:
            // one captured at send time would undo every move the agent made in between
            case ClientboundPlayerPositionPacket teleport when this.player != null -> this.replies.add(teleport.id());
            default -> {
            }
        }
        this.packetsDropped++;
        // callers chain actions such as closing after a kick onto the send future
        if (listener != null) {
            this.channel.newSucceededFuture().addListener(listener);
        }
    }

    // 26.3 added the client's position to the ack; older versions take only the id
    private static ServerboundAcceptTeleportationPacket teleportAck(int id, ServerPlayer player) {
        try {
            if (ACK_WITH_POSITION != null) {
                return ACK_WITH_POSITION.newInstance(id, player.getX(), player.getY(), player.getZ(), player.getYRot(), player.getXRot());
            }
            return ServerboundAcceptTeleportationPacket.class.getConstructor(int.class).newInstance(id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("no known ServerboundAcceptTeleportationPacket constructor", e);
        }
    }

    private static final java.lang.reflect.Constructor<ServerboundAcceptTeleportationPacket> ACK_WITH_POSITION = findAckWithPosition();

    private static java.lang.reflect.Constructor<ServerboundAcceptTeleportationPacket> findAckWithPosition() {
        try {
            return ServerboundAcceptTeleportationPacket.class.getConstructor(int.class, double.class, double.class, double.class, float.class, float.class);
        } catch (NoSuchMethodException e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    void deliverReplies(PacketListener listener) {
        Object reply;
        while ((reply = this.replies.poll()) != null) {
            final Packet<?> packet = reply instanceof Integer id ? teleportAck(id, this.player) : (Packet<?>) reply;
            ((Packet<PacketListener>) packet).handle(listener);
        }
    }
}
