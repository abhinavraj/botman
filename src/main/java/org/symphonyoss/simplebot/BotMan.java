/*
 *
 * Copyright 2016 The Symphony Software Foundation
 *
 * Licensed to The Symphony Software Foundation (SSF) under one
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

package org.symphonyoss.simplebot;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.symphonyoss.client.SymphonyClient;
import org.symphonyoss.client.SymphonyClientFactory;
import org.symphonyoss.client.model.Room;
import org.symphonyoss.client.model.SymAuth;
import org.symphonyoss.client.services.RoomListener;
import org.symphonyoss.client.services.RoomMessage;
import org.symphonyoss.client.services.RoomService;
import org.symphonyoss.client.util.MlMessageParser;
import org.symphonyoss.symphony.agent.model.*;
import org.symphonyoss.symphony.clients.AuthorizationClient;
import org.symphonyoss.symphony.clients.DataFeedClient;
import org.symphonyoss.symphony.pod.model.Stream;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.*;

import static org.symphonyoss.simplebot.BotMan.Command.*;


public class BotMan implements RoomListener {

    private final Logger logger = LoggerFactory.getLogger(BotMan.class);
    private SymphonyClient symClient;
    private Map<String,String> initParams = new HashMap<String,String>();
    private RoomService roomService;
    private Room room;
    private DataFeedClient dataFeedClient;
    private Datafeed datafeed;
    private static final String BASEURL = "http://api.funtranslations.com/translate/";
    private static final String HELP_TEXT = "I am a fun translation bot. \nI support three fun translations : Yoda, Minion, Pirate \nHow to use: \n/yoda textToTranlate \n/minion textToTranslate \n/pirate textToTranslate";

    static Set<String> initParamNames = new HashSet<String>();
    static {
        initParamNames.add("sessionauth.url");
        initParamNames.add("keyauth.url");
        initParamNames.add("pod.url");
        initParamNames.add("agent.url");
        initParamNames.add("truststore.file");
        initParamNames.add("truststore.password");
        initParamNames.add("keystore.password");
        initParamNames.add("certs.dir");
        initParamNames.add("bot.user.name");
        initParamNames.add("bot.user.email");
        initParamNames.add("room.stream");
    }

    public static void main(String[] args) {
        new BotMan();
        System.exit(0);
    }

    public BotMan() {
        initParams();
        initAuth();
        initRoom();
        initDatafeed();
        listenDatafeed();
        
    }

    private void initParams() {
        for(String initParam : initParamNames) {
            String systemProperty = System.getProperty(initParam);
            if (systemProperty == null) {
                throw new IllegalArgumentException("Cannot find system property; make sure you're using -D" + initParam + " to run BotMan");
            } else {
                initParams.put(initParam,systemProperty);
            }
        }
    }

    private void initAuth() {
        try {
            symClient = SymphonyClientFactory.getClient(SymphonyClientFactory.TYPE.BASIC);

            logger.debug("{} {}", System.getProperty("sessionauth.url"),
                    System.getProperty("keyauth.url"));


            AuthorizationClient authClient = new AuthorizationClient(
                    initParams.get("sessionauth.url"),
                    initParams.get("keyauth.url"));


            authClient.setKeystores(
                    initParams.get("truststore.file"),
                    initParams.get("truststore.password"),
                    initParams.get("certs.dir") + initParams.get("bot.user.name") + ".p12",
                    initParams.get("keystore.password"));

            SymAuth symAuth = authClient.authenticate();


            symClient.init(
                    symAuth,
                    initParams.get("bot.user.email"),
                    initParams.get("agent.url"),
                    initParams.get("pod.url")
            );
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    
    private void initRoom() {
        Stream stream = new Stream();
        stream.setId(initParams.get("room.stream"));

        try {
         roomService = new RoomService(symClient);

         room = new Room();
         room.setStream(stream);
         room.setId(stream.getId());
         room.setRoomListener(this);
        } catch (Exception e) {
        	e.printStackTrace();
        }
    }

    public void initDatafeed() {
        dataFeedClient = symClient.getDataFeedClient();
        try {
			datafeed = dataFeedClient.createDatafeed();
		} catch (Exception e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
    }

    private MessageSubmission getMessage(String message) {
        MessageSubmission aMessage = new MessageSubmission();
        aMessage.setFormat(MessageSubmission.FormatEnum.TEXT);
        aMessage.setMessage(message);
        return aMessage;
    }
    
    private V2MessageSubmission getAttachmentMessage() {
    	V2MessageSubmission message = new V2MessageSubmission();
    	List<AttachmentInfo> attachments = new ArrayList();
    	attachments.add(getAttachmentInfo());
    	message.attachments(attachments);
    	return message;
    }

    private AttachmentInfo getAttachmentInfo() {
    	AttachmentInfo attachmentInfo = new AttachmentInfo();
    	return attachmentInfo;
    }
    private void sendMessage(String message) {
        MessageSubmission messageSubmission = getMessage(message);
        try {
            symClient.getMessageService().sendMessage(room, messageSubmission);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void sendMessage(String message, String streamId) {
        Stream stream = new Stream();
        stream.setId(streamId);
        Room room = new Room();
        room.setStream(stream);
        room.setId(stream.getId());
        MessageSubmission messageSubmission = getMessage(message);
        try {
            symClient.getMessageService().sendMessage(room, messageSubmission);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    private void listenDatafeed() {
        while (true) {
            try {
                Thread.sleep(4000);
                MessageList messages = dataFeedClient.getMessagesFromDatafeed(datafeed);
                if (messages != null) {
                    for (Message m : messages) {
                        if (!m.getFromUserId().equals(symClient.getLocalUser().getId())) {
                            processMessage(m);
                        }
                    }
                }

            } catch (InterruptedException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            } catch (Exception e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            }
        }
    }

    private String getPath(Command command) {
        switch (command) {
            case MINION:
                return "minion.json";

            case YODA:
                return "yoda.json";

            case PIRATE:
                return "pirate.json";
        }

        return "minion.json";
    }

    String getCommandText(String text) {
        int idx = text.indexOf(" ");
        return text.substring(idx+1, text.length());
    }

    private void processMessage(Message message) {
        String messageString = message.getMessage();
        if(StringUtils.isNotEmpty(messageString) && StringUtils.isNotBlank(messageString)) {
            MlMessageParser messageParser = new MlMessageParser();
            try {
                messageParser.parseMessage(messageString);
                String text = messageParser.getText();
                Command cmd = getCommand(text);
                if (cmd == HELP) {
                    sendMessage(HELP_TEXT, message.getStreamId());
                } else {
                    String commandText = getCommandText(text);
                    if (commandText != null && !commandText.isEmpty()) {
                        String url = BASEURL + getPath(cmd) + "?text=" + URLEncoder.encode(commandText, "UTF-8");
                        sendMessage(createRequest(new URL(url)), message.getStreamId());
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    private Command getCommand(String text) {
        if(StringUtils.startsWith(text, "/yoda")) {
            return YODA;
        } else if (StringUtils.startsWithIgnoreCase(text, "/pirate")) {
            return PIRATE;
        } else if (StringUtils.startsWithIgnoreCase(text, "/minion")) {
            return MINION;
        } else if (StringUtils.containsIgnoreCase(text, "c-3po")) {
            return HELP;
        } else {
            return UNKNOWN;
        }
    }


    @Override
    public void onRoomMessage(RoomMessage roomMessage) {

        Room room = roomService.getRoom(roomMessage.getId());

        if(room!=null && roomMessage.getMessage() != null)
            logger.debug("New room message detected from room: {} on stream: {} from: {} message: {}",
                    room.getRoomDetail().getRoomAttributes().getName(),
                    roomMessage.getRoomStream().getId(),
                    roomMessage.getMessage().getFromUserId(),
                    roomMessage.getMessage().getMessage()

                );

    }

    public enum Command {
        YODA,
        PIRATE,
        MINION,
        HELP,
        UNKNOWN
    }

    private String createRequest(URL url) {
        HttpURLConnection connection = null;
        InputStream is = null;
        BufferedReader br = null;
        try {
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection .setRequestProperty("Content-Type", "application/json");
            connection.setUseCaches(false);
            connection.setDoOutput(true);

            StringBuffer response = new StringBuffer();
            is = new BufferedInputStream(connection.getInputStream());
            br = new BufferedReader(new InputStreamReader(is));
            String inputLine = "";
            while ((inputLine = br.readLine()) != null) {
                response.append(inputLine);
            }

            return parseResponse(response.toString());
        } catch (Exception e) {
            System.out.print(e.getLocalizedMessage());
        } finally {
            if (connection != null) {
                connection.disconnect();
            }

            try {
                if (is!= null) {
                    is.close();
                }

                if (br != null) {
                    br.close();
                }
            } catch (IOException e) {

            }
        }
        return "Remote API server throttled fun :(. Fun will resume after an hour";
    }

    String parseResponse(String response) {
        JsonParser jsonParser = new JsonParser();
        JsonObject jsonResponse = (JsonObject) jsonParser.parse(response);
        JsonObject contents = jsonResponse.getAsJsonObject("contents");
        String translated = contents.getAsJsonPrimitive("translated").getAsString();
        return translated;
    }
}
    

