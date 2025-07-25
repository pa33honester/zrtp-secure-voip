/**
 * Copyright (C) 2006-2008 Werner Dittmann
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 * Authors: Werner Dittmann <Werner.Dittmann@t-online.de>
 */

package gnu.java.zrtp.packets;

import gnu.java.zrtp.ZrtpConstants;


/**
 * @author Werner Dittmann <Werner.Dittmann@t-online.de>
 *
 */
public class ZrtpPacketConf2Ack extends ZrtpPacketBase {
    
    /**
     * Hello ack does not have any additional fields, just the header.
     */
    private static final int CONF2_ACK_LENGTH = 
        ZRTP_HEADER_LENGTH * ZRTP_WORD_SIZE + CRC_SIZE;

    /**
     * Constructor for a new ErrorAck message.
     * 
     * ErrorAck does not have any specific fields, it is only
     * a simple message.
     *
     */
    public ZrtpPacketConf2Ack() {
        super(new byte[CONF2_ACK_LENGTH]);
        setZrtpId();

        // The length field of a ZRTP packet does not include the CRC field.
        // the length is given in number of ZRTP words.
        setLength(ZRTP_HEADER_LENGTH);
        setMessageType(ZrtpConstants.Conf2AckMsg);
    }

    /**
     * Constructor for ErrorAck message initialized with received data.
     * 
     * @param data received from the network.
     */
    @SuppressWarnings("unused")
    public ZrtpPacketConf2Ack(final byte[] data) {
        super(data);
    }
    
    /* ***
    public static void main(String[] args) {
        ZrtpPacketConf2Ack pkt = new ZrtpPacketConf2Ack();
        System.err.println("conf2Ack length: " + pkt.getLength());
        System.err.println("packetBuffer length in bytes: " + pkt.getHeaderBase().length);
        ZrtpUtils.hexdump("conf2Ack packet", pkt.getHeaderBase(), pkt.getHeaderBase().length);
    }
    *** */
}
