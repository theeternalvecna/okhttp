/*
 * Copyright (C) 2011 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package libcore.net.spdy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import junit.framework.TestCase;

import static libcore.net.spdy.SpdyConnection.TYPE_PING;
import static libcore.net.spdy.SpdyConnection.TYPE_RST_STREAM;
import static libcore.net.spdy.SpdyConnection.TYPE_SYN_REPLY;
import static libcore.net.spdy.SpdyConnection.TYPE_SYN_STREAM;
import static libcore.net.spdy.SpdyStream.RST_INVALID_STREAM;

public final class SpdyConnectionTest extends TestCase {
    private static final IncomingStreamHandler REJECT_INCOMING_STREAMS
            = new IncomingStreamHandler() {
        @Override public void receive(SpdyStream stream) throws IOException {
            throw new AssertionError();
        }
    };
    private final MockSpdyPeer peer = new MockSpdyPeer();

    public void testClientCreatesStreamAndServerReplies() throws Exception {
        // write the mocking script
        peer.acceptFrame();
        SpdyWriter reply = peer.sendFrame();
        reply.id = 1;
        reply.nameValueBlock = Arrays.asList("a", "android");
        reply.synReply();
        SpdyWriter replyData = peer.sendFrame();
        replyData.flags = SpdyConnection.FLAG_FIN;
        replyData.id = 1;
        replyData.data("robot".getBytes("UTF-8"));
        peer.acceptFrame();
        peer.play();

        // play it back
        SpdyConnection connection = new SpdyConnection.Builder(true, peer.openSocket()).build();
        SpdyStream stream = connection.newStream(Arrays.asList("b", "banana"), true, true);
        assertEquals(Arrays.asList("a", "android"), stream.getResponseHeaders());
        assertStreamData("robot", stream.getInputStream());
        writeAndClose(stream, "c3po");

        // verify the peer received what was expected
        MockSpdyPeer.InFrame synStream = peer.takeFrame();
        assertEquals(TYPE_SYN_STREAM, synStream.reader.type);
        assertEquals(0, synStream.reader.flags);
        assertEquals(1, synStream.reader.id);
        assertEquals(0, synStream.reader.associatedStreamId);
        assertEquals(Arrays.asList("b", "banana"), synStream.reader.nameValueBlock);
        MockSpdyPeer.InFrame requestData = peer.takeFrame();
        assertTrue(Arrays.equals("c3po".getBytes("UTF-8"), requestData.data));
    }

    public void testServerCreatesStreamAndClientReplies() throws Exception {
        // write the mocking script
        SpdyWriter newStream = peer.sendFrame();
        newStream.flags = 0;
        newStream.id = 2;
        newStream.associatedId = 0;
        newStream.nameValueBlock = Arrays.asList("a", "android");
        newStream.synStream();
        peer.acceptFrame();
        peer.play();

        // play it back
        final AtomicInteger receiveCount = new AtomicInteger();
        IncomingStreamHandler handler = new IncomingStreamHandler() {
            @Override public void receive(SpdyStream stream) throws IOException {
                receiveCount.incrementAndGet();
                assertEquals(Arrays.asList("a", "android"), stream.getRequestHeaders());
                assertEquals(-1, stream.getRstStatusCode());
                stream.reply(Arrays.asList("b", "banana"));

            }
        };
        new SpdyConnection.Builder(true, peer.openSocket())
                .handler(handler)
                .build();

        // verify the peer received what was expected
        MockSpdyPeer.InFrame reply = peer.takeFrame();
        assertEquals(TYPE_SYN_REPLY, reply.reader.type);
        assertEquals(0, reply.reader.flags);
        assertEquals(2, reply.reader.id);
        assertEquals(0, reply.reader.associatedStreamId);
        assertEquals(Arrays.asList("b", "banana"), reply.reader.nameValueBlock);
        assertEquals(1, receiveCount.get());
    }

    public void testServerPingsClient() throws Exception {
        // write the mocking script
        peer.sendPing(2);
        peer.acceptFrame();
        peer.play();

        // play it back
        new SpdyConnection.Builder(true, peer.openSocket())
                .handler(REJECT_INCOMING_STREAMS)
                .build();

        // verify the peer received what was expected
        MockSpdyPeer.InFrame ping = peer.takeFrame();
        assertEquals(TYPE_PING, ping.reader.type);
        assertEquals(0, ping.reader.flags);
        assertEquals(2, ping.reader.id);
    }

    public void testClientPingsServer() throws Exception {
        // write the mocking script
        peer.acceptFrame();
        peer.sendPing(1);
        peer.play();

        // play it back
        SpdyConnection connection = new SpdyConnection.Builder(true, peer.openSocket())
                .handler(REJECT_INCOMING_STREAMS)
                .build();
        Ping ping = connection.ping();
        assertTrue(ping.roundTripTime() > 0);
        assertTrue(ping.roundTripTime() < TimeUnit.SECONDS.toNanos(1));

        // verify the peer received what was expected
        MockSpdyPeer.InFrame pingFrame = peer.takeFrame();
        assertEquals(TYPE_PING, pingFrame.reader.type);
        assertEquals(0, pingFrame.reader.flags);
        assertEquals(1, pingFrame.reader.id);
    }

    public void testUnexpectedPingIsNotReturned() throws Exception {
        // write the mocking script
        peer.sendPing(2);
        peer.acceptFrame();
        peer.sendPing(3); // This ping will not be returned.
        peer.sendPing(4);
        peer.acceptFrame();
        peer.play();

        // play it back
        new SpdyConnection.Builder(true, peer.openSocket())
                .handler(REJECT_INCOMING_STREAMS)
                .build();

        // verify the peer received what was expected
        MockSpdyPeer.InFrame ping2 = peer.takeFrame();
        assertEquals(2, ping2.reader.id);
        MockSpdyPeer.InFrame ping4 = peer.takeFrame();
        assertEquals(4, ping4.reader.id);
    }

    public void testServerSendsSettingsToClient() throws Exception {
        // write the mocking script
        SpdyWriter newStream = peer.sendFrame();
        newStream.flags = SpdyConnection.FLAG_SETTINGS_CLEAR_PREVIOUSLY_PERSISTED_SETTINGS;
        newStream.settings(1);
        newStream.setting(SpdyConnection.SETTINGS_MAX_CONCURRENT_STREAMS,
                SpdyConnection.FLAG_SETTINGS_PERSIST_VALUE, 10);
        peer.sendPing(2);
        peer.acceptFrame();
        peer.play();

        // play it back
        SpdyConnection connection = new SpdyConnection.Builder(true, peer.openSocket())
                .handler(REJECT_INCOMING_STREAMS)
                .build();

        peer.takeFrame(); // Guarantees that the Settings frame has been processed.
        synchronized (connection) {
            assertEquals(10, connection.peerMaxConcurrentStreams);
        }
    }

    public void testBogusDataFrameDoesNotDisruptConnection() throws Exception {
        // write the mocking script
        SpdyWriter unexpectedData = peer.sendFrame();
        unexpectedData.flags = SpdyConnection.FLAG_FIN;
        unexpectedData.id = 42;
        unexpectedData.data("bogus".getBytes("UTF-8"));
        peer.acceptFrame(); // RST_STREAM
        peer.sendPing(2);
        peer.acceptFrame(); // PING
        peer.play();

        // play it back
        new SpdyConnection.Builder(true, peer.openSocket())
                .handler(REJECT_INCOMING_STREAMS)
                .build();

        // verify the peer received what was expected
        MockSpdyPeer.InFrame rstStream = peer.takeFrame();
        assertEquals(TYPE_RST_STREAM, rstStream.reader.type);
        assertEquals(0, rstStream.reader.flags);
        assertEquals(8, rstStream.reader.length);
        assertEquals(42, rstStream.reader.id);
        assertEquals(RST_INVALID_STREAM, rstStream.reader.statusCode);
        MockSpdyPeer.InFrame ping = peer.takeFrame();
        assertEquals(2, ping.reader.id);
    }

    public void testBogusReplyFrameDoesNotDisruptConnection() throws Exception {
        // write the mocking script
        SpdyWriter unexpectedReply = peer.sendFrame();
        unexpectedReply.nameValueBlock = Arrays.asList("a", "android");
        unexpectedReply.flags = 0;
        unexpectedReply.id = 42;
        unexpectedReply.synReply();
        peer.acceptFrame(); // RST_STREAM
        peer.sendPing(2);
        peer.acceptFrame(); // PING
        peer.play();

        // play it back
        new SpdyConnection.Builder(true, peer.openSocket())
                .handler(REJECT_INCOMING_STREAMS)
                .build();

        // verify the peer received what was expected
        MockSpdyPeer.InFrame rstStream = peer.takeFrame();
        assertEquals(TYPE_RST_STREAM, rstStream.reader.type);
        assertEquals(0, rstStream.reader.flags);
        assertEquals(8, rstStream.reader.length);
        assertEquals(42, rstStream.reader.id);
        assertEquals(RST_INVALID_STREAM, rstStream.reader.statusCode);
        MockSpdyPeer.InFrame ping = peer.takeFrame();
        assertEquals(2, ping.reader.id);
    }

    private void writeAndClose(SpdyStream stream, String data) throws IOException {
        OutputStream out = stream.getOutputStream();
        out.write(data.getBytes("UTF-8"));
        out.close();
    }

    private void assertStreamData(String expected, InputStream inputStream) throws IOException {
        ByteArrayOutputStream bytesOut = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        for (int count; (count = inputStream.read(buffer)) != -1; ) {
            bytesOut.write(buffer, 0, count);
        }
        String actual = bytesOut.toString("UTF-8");
        assertEquals(expected, actual);
    }
}
