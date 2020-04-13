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

package org.geysermc.connector.network.translators.java.world;

import com.nukkitx.protocol.bedrock.data.GameRuleData;
import com.nukkitx.protocol.bedrock.packet.GameRulesChangedPacket;
import it.unimi.dsi.fastutil.longs.Long2BooleanMap;
import it.unimi.dsi.fastutil.longs.Long2BooleanOpenHashMap;
import org.geysermc.connector.network.session.GeyserSession;
import org.geysermc.connector.network.translators.PacketTranslator;
import org.geysermc.connector.network.translators.Translator;

import com.github.steveice10.mc.protocol.packet.ingame.server.world.ServerUpdateTimePacket;
import com.nukkitx.protocol.bedrock.packet.SetTimePacket;

@Translator(packet = ServerUpdateTimePacket.class)
public class JavaUpdateTimeTranslator extends PacketTranslator<ServerUpdateTimePacket> {

    // doDaylightCycle per-player for multi-world support
    static Long2BooleanMap daylightCycles = new Long2BooleanOpenHashMap();

    @Override
    public void translate(ServerUpdateTimePacket packet, GeyserSession session) {

        boolean doDayLightCycle = daylightCycles.getOrDefault(session.getPlayerEntity().getEntityId(), true);
        long time = packet.getTime();

        if ((!doDayLightCycle && time > 0) || (doDayLightCycle && time < 0)) {
            // doDaylightCycle is different than the client and we don't know
            // Time is set either way as a reference point for the current time
            setTime(time, session);
            setDoDayLightGamerule(session, !doDayLightCycle);
        } else if (time > 0) {
            // doDaylightCycle is true and we know
            setTime(time, session);
        }
    }

    private void setTime(long time, GeyserSession session) {
        // https://minecraft.gamepedia.com/Day-night_cycle#24-hour_Minecraft_day
        SetTimePacket setTimePacket = new SetTimePacket();
        setTimePacket.setTime((int) Math.abs(time) % 24000);
        session.getUpstream().sendPacket(setTimePacket);
    }

    private void setDoDayLightGamerule(GeyserSession session, boolean doCycle) {
        GameRulesChangedPacket gameRulesChangedPacket = new GameRulesChangedPacket();
        gameRulesChangedPacket.getGameRules().add(new GameRuleData<>("dodaylightcycle", doCycle));
        session.getUpstream().sendPacket(gameRulesChangedPacket);
        daylightCycles.put(session.getPlayerEntity().getEntityId(), doCycle);
    }

}
