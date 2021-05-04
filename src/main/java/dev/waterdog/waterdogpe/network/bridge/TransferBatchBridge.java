/*
 * Copyright 2021 WaterdogTEAM
 * Licensed under the GNU General Public License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.gnu.org/licenses/old-licenses/gpl-2.0.html
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.waterdog.waterdogpe.network.bridge;

import com.nukkitx.network.raknet.RakNetSession;
import com.nukkitx.protocol.bedrock.BedrockPacket;
import com.nukkitx.protocol.bedrock.BedrockPacketType;
import com.nukkitx.protocol.bedrock.BedrockSession;
import com.nukkitx.protocol.bedrock.handler.BatchHandler;
import com.nukkitx.protocol.bedrock.handler.BedrockPacketHandler;
import com.nukkitx.protocol.bedrock.packet.UnknownPacket;
import dev.waterdog.waterdogpe.player.ProxiedPlayer;
import dev.waterdog.waterdogpe.utils.exceptions.CancelSignalException;
import io.netty.buffer.ByteBuf;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.internal.PlatformDependent;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;

import java.util.Collection;
import java.util.Queue;

public class TransferBatchBridge extends ProxyBatchBridge {

    private final Queue<BedrockPacket> packetQueue = PlatformDependent.newSpscQueue();
    private volatile boolean hasStartGame = false;
    private volatile boolean dimLockActive = true;

    public TransferBatchBridge(ProxiedPlayer player, BedrockSession session) {
        super(player, session);
    }

    @Override
    public void handle(BedrockSession session, ByteBuf buf, Collection<BedrockPacket> packets) {
        super.handle(session, buf, packets);
        if (this.hasStartGame && !this.dimLockActive) {
            // Send queued packets to upstream if dim lock is disabled
            this.flushQueue(session);
        }
    }

    /**
     * Here we send all queued packets from downstream to upstream.
     * Packets will be sent after StartGamePacket is received and dimension change sequence has been passed.
     * Please notice that we use eventLoop of RakNet session instead of BedrockSession because
     * received packets are also handled by RakNet eventLoop!
     *
     * @param downstream instance of BedrockSession which is this handler assigned to.
     */
    public void flushQueue(BedrockSession downstream) {
        RakNetSession rakSession = (RakNetSession) downstream.getConnection();
        if (rakSession.getEventLoop().inEventLoop()) {
            this.flushQueue0();
        } else {
            rakSession.getEventLoop().execute(this::flushQueue0);
        }
    }

    private void flushQueue0() {
        if (this.packetQueue.isEmpty()) {
            return;
        }

        if (this.packetQueue.size() >= 8048) {
            this.player.getLogger().warning("TransferBatchBridge packet queue is too large! Got " + this.packetQueue.size() + " packets with " + this.player.getName());
            this.player.disconnect("Transfer packet queue got too large!");
            // Deallocate packet queue manually because result of TransferBatchBridge#release called
            // from disconnect handler can be ignored as BatchHandler can be already changed.
            this.free0();
            return;
        }

        Collection<BedrockPacket> outboundQueue = new ObjectArrayList<>();
        BedrockPacket packet;
        while ((packet = this.packetQueue.poll()) != null) {
            outboundQueue.add(packet);
        }
        this.session.sendWrapped(outboundQueue, this.session.isEncrypted());
    }

    @Override
    public boolean handlePacket(BedrockPacket packet, BedrockPacketHandler handler) throws CancelSignalException {
        boolean isStartGame = packet.getPacketType() == BedrockPacketType.START_GAME;
        if (isStartGame) {
            this.hasStartGame = true;
        }
        super.handlePacket(packet, handler);
        // Packets after StartGamePacket should be queued
        // Ignore LevelEvent packet to prevent massive amounts of packets in queue
        if (!isStartGame && this.hasStartGame && packet.getPacketType() != BedrockPacketType.LEVEL_EVENT) {
            this.packetQueue.add(ReferenceCountUtil.retain(packet));
        }
        throw CancelSignalException.CANCEL;
    }

    @Override
    public boolean handleUnknownPacket(UnknownPacket packet) {
        if (this.hasStartGame) {
            this.packetQueue.add(packet.retain());
        }
        throw CancelSignalException.CANCEL;
    }

    public void setDimLockActive(boolean active) {
        this.dimLockActive = active;
    }

    public boolean isDimLockActive() {
        return this.dimLockActive;
    }

    public static void release(BatchHandler handler, BedrockSession downstream) {
        if (handler instanceof TransferBatchBridge) {
            ((TransferBatchBridge) handler).free(downstream);
        }
    }

    public void free(BedrockSession downstream) {
        RakNetSession rakSession = (RakNetSession) downstream.getConnection();
        if (rakSession.getEventLoop().inEventLoop()) {
            this.free0();
        } else {
            rakSession.getEventLoop().execute(this::free0);
        }
    }

    private void free0() {
        BedrockPacket packet;
        while ((packet = this.packetQueue.poll()) != null) {
            int refCnt = ReferenceCountUtil.refCnt(packet);
            if (refCnt > 0) {
                ReferenceCountUtil.release(packet, refCnt);
            }
        }
    }
}
