/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.pulsar.proxy.server;


import avro.shaded.com.google.common.collect.Lists;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.buffer.CompositeByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import org.apache.pulsar.common.api.proto.PulsarApi;
import org.apache.pulsar.common.api.raw.MessageParser;
import org.apache.pulsar.common.api.raw.RawMessage;
import org.apache.pulsar.common.naming.TopicName;
import org.apache.pulsar.common.util.protobuf.ByteBufCodedInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;


public class ParserProxyHandler extends ChannelInboundHandlerAdapter {


    private Channel channel;
    //inbound
    protected static final String FRONTEND_CONN = "frontendconn";
    //outbound
    protected static final String BACKEND_CONN = "backendconn";

    private String connType;

    private int maxMessageSize;


    //producerid+channelid as key
    //or consumerid+channelid as key
    private static Map<String, String> producerHashMap = new ConcurrentHashMap<>();
    private static Map<String, String> consumerHashMap = new ConcurrentHashMap<>();

    public ParserProxyHandler(Channel channel, String type, int maxMessageSize){
        this.channel = channel;
        this.connType=type;
        this.maxMessageSize = maxMessageSize;
    }

    private void logging(Channel conn, PulsarApi.BaseCommand.Type cmdtype, String info, List<RawMessage> messages) throws Exception{

        if (messages != null) {
            // lag
            for (int i=0; i<messages.size(); i++) {
                info = info + "["+ (System.currentTimeMillis() - messages.get(i).getPublishTime()) + "] " + new String(ByteBufUtil.getBytes((messages.get(i)).getData()), "UTF8");
            }
        }
        // log conn format is like from source to target
        switch (this.connType) {
            case ParserProxyHandler.FRONTEND_CONN:
                log.info(ParserProxyHandler.FRONTEND_CONN + ":{} cmd:{} msg:{}", "[" + conn.remoteAddress() + conn.localAddress() + "]", cmdtype, info);
                break;
            case ParserProxyHandler.BACKEND_CONN:
                log.info(ParserProxyHandler.BACKEND_CONN + ":{} cmd:{} msg:{}", "[" + conn.localAddress() + conn.remoteAddress() + "]", cmdtype, info);
                break;
        }
    }

    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        PulsarApi.BaseCommand cmd = null;
        PulsarApi.BaseCommand.Builder cmdBuilder = null;
        TopicName topicName ;
        List<RawMessage> messages = Lists.newArrayList();
        ByteBuf buffer = (ByteBuf)(msg);

        try {
            buffer.markReaderIndex();
            buffer.markWriterIndex();

            int cmdSize = (int) buffer.readUnsignedInt();
            int writerIndex = buffer.writerIndex();
            buffer.writerIndex(buffer.readerIndex() + cmdSize);

            ByteBufCodedInputStream cmdInputStream = ByteBufCodedInputStream.get(buffer);
            cmdBuilder = PulsarApi.BaseCommand.newBuilder();
            cmd = cmdBuilder.mergeFrom(cmdInputStream, null).build();
            buffer.writerIndex(writerIndex);
            cmdInputStream.recycle();

            switch (cmd.getType()) {
                case PRODUCER:
                    ParserProxyHandler.producerHashMap.put(String.valueOf(cmd.getProducer().getProducerId()) + "," + String.valueOf(ctx.channel().id()), cmd.getProducer().getTopic());

                    logging(ctx.channel() , cmd.getType() , "{producer:" + cmd.getProducer().getProducerName() + ",topic:" + cmd.getProducer().getTopic() + "}", null);
                    break;

                case SEND:
                    if (ProxyService.proxyLogLevel != 2) {
                        logging(ctx.channel() , cmd.getType() , "", null);
                        break;
                    }
                    topicName = TopicName.get(ParserProxyHandler.producerHashMap.get(String.valueOf(cmd.getProducer().getProducerId()) + "," + String.valueOf(ctx.channel().id())));
                    MessageParser.parseMessage(topicName,  -1L,
                            -1L,buffer,(message) -> {
                                messages.add(message);
                            }, maxMessageSize);

                    logging(ctx.channel() , cmd.getType() , "" , messages);
                    break;

                case SUBSCRIBE:
                    ParserProxyHandler.consumerHashMap.put(String.valueOf(cmd.getSubscribe().getConsumerId()) + "," + String.valueOf(ctx.channel().id()) , cmd.getSubscribe().getTopic());

                    logging(ctx.channel() , cmd.getType() , "{consumer:" + cmd.getSubscribe().getConsumerName() + ",topic:" + cmd.getSubscribe().getTopic() + "}" , null);
                    break;

                case MESSAGE:
                    if (ProxyService.proxyLogLevel != 2) {
                        logging(ctx.channel() , cmd.getType() , "" , null);
                        break;
                    }
                    topicName = TopicName.get(ParserProxyHandler.consumerHashMap.get(String.valueOf(cmd.getMessage().getConsumerId()) + "," + DirectProxyHandler.inboundOutboundChannelMap.get(ctx.channel().id())));
                    MessageParser.parseMessage(topicName,  -1L,
                                -1L,buffer,(message) -> {
                                    messages.add(message);
                                }, maxMessageSize);


                    logging(ctx.channel() , cmd.getType() , "" , messages);
                    break;

                 default:
                    logging(ctx.channel() , cmd.getType() , "" , null);
                    break;
            }
        } catch (Exception e){

            log.error("{},{},{}" , e.getMessage() , e.getStackTrace() ,  e.getCause());

        } finally {

            if (cmdBuilder != null) {
                cmdBuilder.recycle();
            }
            if (cmd != null) {
                cmd.recycle();
            }
            buffer.resetReaderIndex();
            buffer.resetWriterIndex();

            // add totalSize to buffer Head
            ByteBuf totalSizeBuf = Unpooled.buffer(4);
            totalSizeBuf.writeInt(buffer.readableBytes());
            CompositeByteBuf compBuf = Unpooled.compositeBuffer();
            compBuf.addComponents(totalSizeBuf,buffer);
            compBuf.writerIndex(totalSizeBuf.capacity()+buffer.capacity());

            //next handler
            ctx.fireChannelRead(compBuf);
        }
    }

    private static final Logger log = LoggerFactory.getLogger(ParserProxyHandler.class);
}
