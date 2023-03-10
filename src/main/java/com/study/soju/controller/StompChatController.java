package com.study.soju.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import com.study.soju.dto.ChatMessageDTO;
import com.study.soju.dto.MetaCanvasDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.QueueInformation;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

@Controller
@RequiredArgsConstructor
public class StompChatController {
    // @MessageMapping으로 받아온 메시지를 다시 클라이언트로 전달해주는 SimpMessagingTemplate
    @Autowired
    SimpMessagingTemplate template; // 특정 Broker로 메시지를 전달한다.

    // JSON 형식의 데이터를 Java 객체로 역직렬화(Deserialize)하거나, 반대로 Java 객체를 JSON 형식의 데이터로 직렬화(Serialize)할 때 사용하는 Jackson 라이브러리의 클래스이다.
    @Autowired
    private ObjectMapper objectMapper;

    // RabbitMQ 메시징 서비스와 상호 작용하기 위한 스프링 AMQP 프로젝트의 클래스이다.
    // 이 클래스를 사용하여 RabbitMQ 큐로 메시지를 전송하고, 큐에서 메시지를 수신하여 이를 처리할 수 있다.
    // RabbitMQ의 메시지 브로커와 상호작용하면서 필요한 메시지 변환, 라우팅, 중복 처리 등의 기능을 제공한다.
    @Autowired
    RabbitTemplate rabbitTemplate;

    // RabbitMQ 서버에게 관리 작업을 수행하기 위한 API를 제공하는 클래스이다.
    @Autowired
    private RabbitAdmin rabbitAdmin;

    // 지정한 이름의 RabbitMQ 큐에 현재 쌓여있는 메시지 수를 반환한다.
    public int getMessageCount(String queueName) { // 파라미터로 큐 이름을 받아온다.
        // getQueueInfo - RabbitAdmin 객체의 메소드로서, 파라미터로 받아온 큐 이름의 큐 정보를 조회한다.
        // QueueInformation 객체로 반환되며, 해당 큐의 속성 정보와 현재 큐에 쌓여있는 메시지 수 등의 정보를 담고 있다.
        QueueInformation queueInformation = rabbitAdmin.getQueueInfo(queueName);
        // getMessageCount - QueueInformation 객체의 메소드로서, 현재 큐에 쌓여있는 메시지 수를 반환한다.
        return queueInformation.getMessageCount();
    }

    // 방에 참가한 유저의 캐릭터 정보들을 보낼때 방 번호로 구분하기 위한 Map
    Map<Long, Map<String, List<Object>>> metaRoomMap = new HashMap<>();

    // 메시지를 보낼 때 퇴장 메시지와 재입장 메시지를 관리하기 위한 ConcurrentHashMap
    ConcurrentHashMap<String, ChatMessageDTO> metaMessageMap = new ConcurrentHashMap<>();

    // Client에서 전송한 SEND 요청을 처리
    // @MessageMapping - 클라이언트에서 요청을 보낸 URI 에 대응하는 메소드로 연결을 해주는 역할을 한다.
    // StompWebSocketConfig에서 설정한 applicationDestinationPrefixes와 @MessageMapping 경로가 자동으로 병합된다.
    // "/pub" + "/meta/studyRoom/enter" = "/pub/meta/studyRoom/enter"
////////////////////////////////////////////////// 스터디룸 구역 //////////////////////////////////////////////////
    // 스터디룸 첫 입장
    @MessageMapping(value = "/meta/studyRoom/enter")
    public void enterStudyRoom(ChatMessageDTO message) { // 1. 클라이언트로부터 전송된 첫 입장 정보들을 DTO로 받아온다.
        // 2. 1에서 파라미터로 받아온 DTO 값 중 작성자를 가져와 참여메세지를 작성해 DTO 값 중 메세지에 저장한다.
        message.setMessage(message.getWriter() + "님이 채팅방에 참여하였습니다.");
        // 3. 1에서 파라미터로 받아온 DTO 값 중 작성자를 가져와 참여자로 저장한다.
        message.setParticipant(message.getWriter());
        // 4. SimpMessagingTemplate를 통해 해당 path를 SUBSCRIBE하는 Client에게 DTO를 다시 전달한다.
        //    path : StompWebSocketConfig에서 설정한 enableSimpleBroker와 DTO를 전달할 경로와 1에서 파라미터로 받아온 DTO 값 중 방 번호가 병합된다.
        //    "/sub" + "/meta/studyRoom/" + metaIdx = "/sub/meta/studyRoom/1"
        template.convertAndSend("/sub/meta/studyRoom/" + message.getMetaIdx(), message);
    }

    // 스터디룸 첫 입장 이후 재입장 - 첫 입장 이후 모든 재입장은 이곳으로 들어온다.
    @MessageMapping(value = "/meta/studyRoom/reenter")
    public void reEnterStudyRoom(ChatMessageDTO message) { // 1. 클라이언트로부터 전송된 재입장(새로고침) 정보들을 DTO로 받아온다.
        // 2. 이전 퇴장 메소드에서 Map에 추가한 키에 해당하는 DTO를 다시 가져온다.
        ChatMessageDTO exitMessage = metaMessageMap.get(message.getMetaIdx() + "_exit");
        // 3. 2에서 가져온 DTO가 여전히 존재하는지 체크한다.
        // 3-1. 퇴장 메시지가 존재하는 경우 - 재입장(새로고침)
        if (exitMessage != null) {
            // 3-1-1. 이번 퇴장 메소드에서 Map에 추가한 키에 해당하는 DTO를 삭제한다.
            metaMessageMap.remove(message.getMetaIdx() + "_exit");
            // 3-1-2. SimpMessagingTemplate를 통해 해당 path를 SUBSCRIBE하는 Client에게 DTO를 다시 전달한다.
            //        path : StompWebSocketConfig에서 설정한 enableSimpleBroker와 DTO를 전달할 경로와 1에서 파라미터로 받아온 DTO 값 중 방 번호가 병합된다.
            //        "/sub" + "/meta/studyRoom/" + metaIdx = "/sub/meta/studyRoom/1"
            template.convertAndSend("/sub/meta/studyRoom/" + message.getMetaIdx(), message);
        // 3-2. 퇴장 메시지가 존재하지 않는 경우 - 1초가 넘는 장시간의 새로고침 에러로 인한 퇴장 처리 후 재입장
        //                                 이는 아직 퇴장한 것이 아닌데 퇴장 처리가 되었으므로 다시 입장 처리를 해준다.
        } else {
            // 3-2-1. 1에서 파라미터로 받아온 DTO 값 중 작성자를 가져와 참여메세지를 작성해 DTO 값 중 메세지에 저장한다.
            message.setMessage(message.getWriter() + "님이 채팅방에 재입장하였습니다.");
            // 3-2-2. 1에서 파라미터로 받아온 DTO 값 중 작성자를 가져와 참여자로 저장한다.
            message.setParticipant(message.getWriter());
            // 3-2-3. SimpMessagingTemplate를 통해 해당 path를 SUBSCRIBE하는 Client에게 DTO를 다시 전달한다.
            //        path : StompWebSocketConfig에서 설정한 enableSimpleBroker와 DTO를 전달할 경로와 1에서 파라미터로 받아온 DTO 값 중 방 번호가 병합된다.
            //        "/sub" + "/meta/studyRoom/" + metaIdx = "/sub/meta/studyRoom/1"
            template.convertAndSend("/sub/meta/studyRoom/" + message.getMetaIdx(), message);
        }
    }

    // 스터디룸 채팅
    @MessageMapping(value = "/meta/studyRoom/message")
    public void messageStudyRoom(ChatMessageDTO message) { // 1. 클라이언트로부터 전송된 채팅 정보들을 DTO로 받아온다.
        // 2. SimpMessagingTemplate를 통해 해당 path를 SUBSCRIBE하는 Client에게 DTO를 다시 전달한다.
        //    path : StompWebSocketConfig에서 설정한 enableSimpleBroker와 DTO를 전달할 경로와 1에서 파라미터로 받아온 DTO 값 중 방 번호가 병합된다.
        //    "/sub" + "/meta/studyRoom/" + metaIdx = "/sub/meta/studyRoom/1"
        template.convertAndSend("/sub/meta/studyRoom/" + message.getMetaIdx(), message);
    }

    // 스터디룸 퇴장
    @MessageMapping(value = "/meta/studyRoom/exit")
    // Future - Future 인터페이스는 Java5부터 java.util.concurrency 패키지에서 비동기의 결과값을 받는 용도로 사용했지만 비동기의 결과값을 조합하거나, error를 핸들링할 수가 없었다.
    // CompletionStage - Java 8에서 추가된 인터페이스 중 하나로, 비동기식 계산 결과를 다루기 위한 일종의 통합 API이다.
    //                   CompletableFuture 클래스와 함께 사용되어, 비동기 작업을 수행하고 작업 결과를 처리하는 기능을 제공한다.
    // CompletableFuture - Java 8에서 추가된 클래스 중 하나로, 비동기 작업을 수행하고 해당 작업의 결과를 처리하는 기능을 제공한다.
    // <Void> - runAsync는 반환 값이 없으므로 Void 타입이다.
    public CompletableFuture<Void> exitStudyRoom(ChatMessageDTO message) { // 1. 클라이언트로부터 전송된 퇴장 정보들을 DTO로 받아온다.
        // 2. 받아온 DTO 값 중 작성자를 가져와 퇴장 메시지를 작성해 DTO 값 중 메시지에 저장한다.
        message.setMessage(message.getWriter() + "님이 채팅방에서 탈주하였습니다.");
        // 3. 1에서 파라미터로 받아온 DTO 값 중 작성자를 가져와 퇴장자로 저장한다.
        message.setExit(message.getWriter());
        // 4. 1에서 파라미터로 받아온 DTO 값 중 참가중인 인원을 가져와 1을 감소한뒤 다시 참가중인 인원에 저장한다.
        message.setMetaRecruitingPersonnel(message.getMetaRecruitingPersonnel() - 1);

        // 5. 1에서 파라미터로 받아온 DTO 값 중 방 번호와 퇴장을 의미하는 문자를 조합하여 키로 사용하고, 1에서 파라미터로 받아온 DTO를 값으로 사용하여 Map에 추가한다.
        metaMessageMap.put(message.getMetaIdx() + "_exit", message);

        // CompletableFuture.runAsync - 비동기적으로 실행되는 작업을 수행하는 CompletableFuture 객체를 반환한다.
        // runAsync() - 파라미터로 Runnable 객체를 받으며, 이 객체의 run() 메소드 안에 비동기적으로 실행할 작업을 구현한다.
        //              메소드는 즉시 리턴하며, 별도의 쓰레드에서 run() 메소드 안에 구현된 작업이 비동기적으로 실행된다.
        // Runnable - 인자를 받지 않고, 리턴값도 없는 함수형 인터페이스이다.
        //            run() 메소드를 하나만 가지고 있으며, 이 메소드에서 수행될 작업을 구현한다.
        //            run() 메소드는 매개변수를 받지 않으며, 리턴값도 없다.
        return CompletableFuture.runAsync(() -> {
            try {
                // 6. 퇴장 메시지를 전송하기 전에 1초 대기하여 퇴장인지 재입장(새로고침)인지 체크한다.
                Thread.sleep(1000);
                // 7. 5에서 Map에 추가한 키에 해당하는 DTO를 다시 가져온다.
                ChatMessageDTO exitMessage = metaMessageMap.get(message.getMetaIdx() + "_exit");
                // 8. 6에서 1초 대기한 후에도 7에서 가져온 DTO가 여전히 존재하는지 체크한다.
                // 8-1. 퇴장 메시지가 존재하는 경우 - 퇴장
                if ( exitMessage != null ) {
                    // 8-1-1. SimpMessagingTemplate를 통해 해당 path를 SUBSCRIBE하는 Client에게 DTO를 다시 전달한다.
                    //        path : StompWebSocketConfig에서 설정한 enableSimpleBroker와 DTO를 전달할 경로와 1에서 파라미터로 받아온 DTO 값 중 방 번호가 병합된다.
                    //        "/sub" + "/meta/studyRoom/" + metaIdx = "/sub/meta/studyRoom/1"
                    template.convertAndSend("/sub/meta/studyRoom/" + message.getMetaIdx(), message);
                    // 8-1-2. 5에서 Map에 추가한 키에 해당하는 DTO를 삭제한다.
                    metaMessageMap.remove(message.getMetaIdx() + "_exit");
                    // 8-1-3. 위에서 생성한 방 구분용 Map에서, 1에서 파라미터로 받아온 DTO 값 중 방 번호 키에 해당하는 Map을 가져온다.
                    Map<String, List<Object>> metaCanvasMap = metaRoomMap.get(message.getMetaIdx());
                    // 8-1-4. 8-1-3에서 가져온 Map에서, 1에서 파라미터로 받아온 DTO 값 중 닉네임 키에 해당하는 List를 제거한다.
                    metaCanvasMap.remove(message.getExit());
                    String metaCanvasJson = objectMapper.writeValueAsString(metaCanvasMap);
                    message.setExit(metaCanvasJson);
                    template.convertAndSend("/sub/meta/studyRoom/canvas/" + message.getMetaIdx(), message);
                // 8-2. 퇴장 메시지가 존재하지 않는 경우 - 재입장(새로고침)
                } else {
                    // 8-2-1. 퇴장한 것이 아니기에 더 이상 작업할 것이 없다.
                }
            } catch (InterruptedException e) { // 스레드를 중지하거나 중단시킬 때 발생할 수 있는 예외
                throw new RuntimeException(e);
            } catch (JsonProcessingException e) {
                throw new RuntimeException(e);
            }
        });
    }
////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    // 스터디룸 캔버스 첫 입장
    @MessageMapping(value = "/meta/studyRoom/canvas/enter")
    public void canvasEnterStudyRoom(MetaCanvasDTO canvas) throws JsonProcessingException { // 1. 클라이언트로부터 전송된 캐릭터 정보들을 DTO로 받아온다.
        // 2. 위에서 생성한 방 구분용 Map에서, 1에서 파라미터로 받아온 DTO 값 중 방 번호 키에 해당하는 Map을 가져온다.
        Map<String, List<Object>> metaCanvasMap = metaRoomMap.get(canvas.getMetaIdx());
        // 3. 2에서 가져온 Map이 존재하는지 체크한다.
        // 3-1. Map이 존재하지 않는 경우
        if ( metaCanvasMap == null ) {
            // 3-1-1. 2에서 가져오는 Map을 생성한다.
            metaCanvasMap = new HashMap<>();
            // 3-1-2. 3-1-1에서 생성한 Map에 값으로 사용할 List를 생성한다.
            List<Object> metaCoordinateList = new ArrayList();
            // 3-1-3. 3-1-2에서 생성한 List에 1에서 파라미터로 받아온 DTO 값 중 캐릭터 이름을 전달한다.
            metaCoordinateList.add(canvas.getCharacter());
            // 3-1-4. 3-1-2에서 생성한 List에 1에서 파라미터로 받아온 DTO 값 중 x축 좌표를 전달한다.
            metaCoordinateList.add(canvas.getX());
            // 3-1-5. 3-1-2에서 생성한 List에 1에서 파라미터로 받아온 DTO 값 중 y축 좌표를 전달한다.
            metaCoordinateList.add(canvas.getY());
            // 3-1-6. 1에서 파라미터로 받아온 DTO 값 중 닉네임을 키로 사용하고, 3-1-2에서 생성한 List를 값으로 사용하여 3-1-1에서 생성한 Map에 추가한다.
            metaCanvasMap.put(canvas.getWriter(), metaCoordinateList);
            // 3-1-6. 1에서 파라미터로 받아온 DTO 값 중 방 번호를 키로 사용하고, 3-1-6에서 추가한 Map을 값으로 사용하여 위에서 생성한 Map에 추가한다.
            metaRoomMap.put(canvas.getMetaIdx(), metaCanvasMap);
            // 3-1-7. 위에서 @Autowired로 생성한 ObjectMapper를 사용하여 3-1-1에서 생성한 Map을 JSON 문자열로 변환한다.
            String metaCanvasJson = objectMapper.writeValueAsString(metaCanvasMap);
            // 3-1-8. 3-1-7에서 변환한 JSON 문자열을 1에서 파라미터로 받아온 DTO 값 중 캐릭터 정보에 setter를 통해 전달한다.
            canvas.setCharacters(metaCanvasJson);
            // 3-1-9. SimpMessagingTemplate를 통해 해당 path를 SUBSCRIBE하는 Client에게 DTO를 다시 전달한다.
            //        path : StompWebSocketConfig에서 설정한 enableSimpleBroker와 DTO를 전달할 경로와 1에서 파라미터로 받아온 DTO 값 중 방 번호가 병합된다.
            //        "/sub" + "/meta/studyRoom/canvas/" + metaIdx = "/sub/meta/studyRoom/canvas/1"
            template.convertAndSend("/sub/meta/studyRoom/canvas/" + canvas.getMetaIdx(), canvas);
        // 3-2. Map이 존재하는 경우
        } else {
            // 3-2-1. 2에서 가져온 Map에 값으로 사용할 List를 생성한다.
            List<Object> metaCoordinateList = new ArrayList();
            // 3-2-2. 3-2-1에서 생성한 List에 1에서 파라미터로 받아온 DTO 값 중 캐릭터 이름을 전달한다.
            metaCoordinateList.add(canvas.getCharacter());
            // 3-2-3. 3-2-1에서 생성한 List에 1에서 파라미터로 받아온 DTO 값 중 x축 좌표를 전달한다.
            metaCoordinateList.add(canvas.getX());
            // 3-2-4. 3-2-1에서 생성한 List에 1에서 파라미터로 받아온 DTO 값 중 y축 좌표를 전달한다.
            metaCoordinateList.add(canvas.getY());
            // 3-2-5. 1에서 파라미터로 받아온 DTO 값 중 닉네임을 키로 사용하고, 3-2-1에서 생성한 List를 값으로 사용하여 2에서 가져온 Map에 추가한다.
            metaCanvasMap.put(canvas.getWriter(), metaCoordinateList);
            // 3-2-6. 위에서 @Autowired로 생성한 ObjectMapper를 사용하여 2에서 가져온 Map을 JSON 문자열로 변환한다.
            String metaCanvasJson = objectMapper.writeValueAsString(metaCanvasMap);
            // 3-2-7. 3-2-6에서 변환한 JSON 문자열을 1에서 파라미터로 받아온 DTO 값 중 캐릭터 정보에 setter를 통해 전달한다.
            canvas.setCharacters(metaCanvasJson);
            // 3-2-8. SimpMessagingTemplate를 통해 해당 path를 SUBSCRIBE하는 Client에게 DTO를 다시 전달한다.
            //        path : StompWebSocketConfig에서 설정한 enableSimpleBroker와 DTO를 전달할 경로와 1에서 파라미터로 받아온 DTO 값 중 방 번호가 병합된다.
            //        "/sub" + "/meta/studyRoom/canvas/" + metaIdx = "/sub/meta/studyRoom/canvas/1"
            template.convertAndSend("/sub/meta/studyRoom/canvas/" + canvas.getMetaIdx(), canvas);
        }
    }

    // 스터디룸 캔버스 첫 입장 이후 재입장 - 첫 입장 이후 모든 재입장은 이곳으로 들어온다.
    @MessageMapping(value = "/meta/studyRoom/canvas/reenter")
    public void canvasReEnterStudyRoom(MetaCanvasDTO canvas) throws JsonProcessingException { // 1. 클라이언트로부터 전송된 캐릭터 정보들을 DTO로 받아온다.
        // 2. 위에서 생성한 방 구분용 Map에서, 1에서 파라미터로 받아온 DTO 값 중 방 번호 키에 해당하는 Map을 가져온다.
        Map<String, List<Object>> metaCanvasMap = metaRoomMap.get(canvas.getMetaIdx());
        // 3. 2에서 가져온 Map에서, 1에서 파라미터로 받아온 DTO 값 중 닉네임 키에 해당하는 List를 가져온다.
        List<Object> metaCoordinateList = metaCanvasMap.get(canvas.getWriter());
        // 4. 3에서 가져온 List 값 중 x좌표 값을, 1에서 파라미터로 받아온 DTO 값 중 x좌표 값으로 갱신한다.
        metaCoordinateList.set(1, canvas.getX());
        // 5. 3에서 가져온 List 값 중 y좌표 값을, 1에서 파라미터로 받아온 DTO 값 중 y좌표 값으로 갱신한다.
        metaCoordinateList.set(2, canvas.getY());
        // 6. 위에서 @Autowired로 생성한 ObjectMapper를 사용하여 2에서 가져온 Map을 JSON 문자열로 변환한다.
        String metaCanvasJson = objectMapper.writeValueAsString(metaCanvasMap);
        // 7. 6에서 변환한 JSON 문자열을 1에서 파라미터로 받아온 DTO 값 중 캐릭터 정보에 setter를 통해 전달한다.
        canvas.setCharacters(metaCanvasJson);
        // 8. SimpMessagingTemplate를 통해 해당 path를 SUBSCRIBE하는 Client에게 DTO를 다시 전달한다.
        //    path : StompWebSocketConfig에서 설정한 enableSimpleBroker와 DTO를 전달할 경로와 1에서 파라미터로 받아온 DTO 값 중 방 번호가 병합된다.
        //    "/sub" + "/meta/studyRoom/canvas/" + metaIdx = "/sub/meta/studyRoom/canvas/1"
        template.convertAndSend("/sub/meta/studyRoom/canvas/" + canvas.getMetaIdx(), canvas);
    }

    // 스터디룸 캔버스 캐릭터 이동 좌표 변경 - RabbitMQ 사용
    @MessageMapping(value = "/meta/studyRoom/canvas/move")
    public void canvasMoveStudyRoom(MetaCanvasDTO canvas) throws JsonProcessingException { // 1. 클라이언트로부터 전송된 캐릭터 정보들을 DTO로 받아온다.
        // 2. 위에서 생성한 방 구분용 Map에서, 1에서 파라미터로 받아온 DTO 값 중 방 번호 키에 해당하는 Map을 가져온다.
        Map<String, List<Object>> metaCanvasMap = metaRoomMap.get(canvas.getMetaIdx());
        // 3. 2에서 가져온 Map에서, 1에서 파라미터로 받아온 DTO 값 중 닉네임 키에 해당하는 List를 가져온다.
        List<Object> metaCoordinateList = metaCanvasMap.get(canvas.getWriter());
        // 4. 1에서 파라미터로 받아온 DTO 값 중 메시지 타입을 가져와 이동 분기를 결정한다.
        switch( canvas.getType() ) {
            // 4-1. 왼쪽으로 이동
            case "left":
                // 왼쪽 벽이 나오면 멈춘다.
                if ( (int) metaCoordinateList.get(1) < canvas.getCanvasLeft() ) {
                    // 위쪽 벽이 나오면 멈춘다.
                    if ( (int) metaCoordinateList.get(2) < canvas.getCanvasTop() ) {
                        break;
                    }
                    // 아래쪽 벽이 나오면 멈춘다.
                    if ( (int) metaCoordinateList.get(2) > canvas.getCanvasBottom() ) {
                        break;
                    }
                    break;
                // 왼쪽 벽이 나오기 전까지 움직인다.
                } else {
                    // 4-1-1. 3에서 가져온 List 값 중 x좌표 값을, 5 뺀 값으로 갱신한다.
                    metaCoordinateList.set(1, (int) metaCoordinateList.get(1) - 5);
                    // 위쪽 벽이 나오면 멈춘다.
                    if ( (int) metaCoordinateList.get(2) < canvas.getCanvasTop() ) {
                        break;
                    }
                    // 아래쪽 벽이 나오면 멈춘다.
                    if ( (int) metaCoordinateList.get(2) > canvas.getCanvasBottom() ) {
                        break;
                    }
                    break;
                }
            // 4-2. 위로 이동
            case "top":
                // 위쪽 벽이 나오면 멈춘다.
                if ( (int) metaCoordinateList.get(2) < canvas.getCanvasTop() ) {
                    // 왼쪽 벽이 나오면 멈춘다.
                    if ( (int) metaCoordinateList.get(1) < canvas.getCanvasLeft() ) {
                        break;
                    }
                    // 오른쪽 벽이 나오면 멈춘다.
                    if ( (int) metaCoordinateList.get(1) > canvas.getCanvasRight() ) {
                        break;
                    }
                    break;
                // 위쪽 벽이 나오기 전까지 움직인다.
                } else {
                    // 4-2-1. 3에서 가져온 List 값 중 y좌표 값을, 5 뺀 값으로 갱신한다.
                    metaCoordinateList.set(2, (int) metaCoordinateList.get(2) - 5);
                    // 왼쪽 벽이 나오면 멈춘다.
                    if ( (int) metaCoordinateList.get(1) < canvas.getCanvasLeft() ) {
                        break;
                    }
                    // 오른쪽 벽이 나오면 멈춘다.
                    if ( (int) metaCoordinateList.get(1) > canvas.getCanvasRight() ) {
                        break;
                    }
                    break;
                }
            // 4-3. 오른쪽으로 이동
            case "right":
                // 오른쪽 벽이 나오면 멈춘다.
                if ( (int) metaCoordinateList.get(1) > canvas.getCanvasRight() ) {
                    // 위쪽 벽이 나오면 멈춘다.
                    if ( (int) metaCoordinateList.get(2) < canvas.getCanvasTop() ) {
                        break;
                    }
                    // 아래쪽 벽이 나오면 멈춘다.
                    if ( (int) metaCoordinateList.get(2) > canvas.getCanvasBottom() ) {
                        break;
                    }
                    break;
                // 오른쪽 벽이 나오기 전까지 움직인다.
                } else {
                    // 4-3-1. 3에서 가져온 List 값 중 x좌표 값을, 5 더한 값으로 갱신한다.
                    metaCoordinateList.set(1, (int) metaCoordinateList.get(1) + 5);
                    // 위쪽 벽이 나오면 멈춘다.
                    if ( (int) metaCoordinateList.get(2) < canvas.getCanvasTop() ) {
                        break;
                    }
                    // 아래쪽 벽이 나오면 멈춘다.
                    if ( (int) metaCoordinateList.get(2) > canvas.getCanvasBottom() ) {
                        break;
                    }
                    break;
                }
            // 4-4. 아래로 이동
            case "bottom":
                // 아래쪽 벽이 나오면 멈춘다.
                if ( (int) metaCoordinateList.get(2) > canvas.getCanvasBottom() ) {
                    // 왼쪽 벽이 나오면 멈춘다.
                    if ( (int) metaCoordinateList.get(1) < canvas.getCanvasLeft() ) {
                        break;
                    }
                    // 오른쪽 벽이 나오면 멈춘다.
                    if ( (int) metaCoordinateList.get(1) > canvas.getCanvasRight() ) {
                        break;
                    }
                    break;
                // 아래쪽 벽이 나오기 전까지 움직인다.
                } else {
                    // 4-4-1. 3에서 가져온 List 값 중 y좌표 값을, 5 더한 값으로 갱신한다.
                    metaCoordinateList.set(2, (int) metaCoordinateList.get(2) + 5);
                    // 왼쪽 벽이 나오면 멈춘다.
                    if ( (int) metaCoordinateList.get(1) < canvas.getCanvasLeft() ) {
                        break;
                    }
                    // 오른쪽 벽이 나오면 멈춘다.
                    if ( (int) metaCoordinateList.get(1) > canvas.getCanvasRight() ) {
                        break;
                    }
                    break;
                }
        }
        // 5. 위에서 @Autowired로 생성한 ObjectMapper를 사용하여 2에서 가져온 Map을 JSON 문자열로 변환한다.
        String metaCanvasJson = objectMapper.writeValueAsString(metaCanvasMap);
        // 6. 5에서 변환한 JSON 문자열을 1에서 파라미터로 받아온 DTO 값 중 캐릭터 정보에 setter를 통해 전달한다.
        canvas.setCharacters(metaCanvasJson);

        // 7번과 8번은 RabbitMQConfig에서 미리 메시지 변환기를 만들어 설정해놨기에 사용할 필요가 없다.
        // 7. 위에서 @Autowired로 생성한 ObjectMapper를 사용하여 1에서 파라미터로 받아온 DTO를 JSON 문자열로 변환한다.
//        String json = objectMapper.writeValueAsString(canvas);
        // 8. Spring AMQP의 MessageBuilder 클래스를 사용하여 AMQP 메시지를 생성한다.
        //    MessageBuilder.withBody(json.getBytes()) - 7에서 변환한 JSON 문자열을 byte 배열로 변환하여 AMQP 메시지의 body에 전달한다.
        //    .setContentType("application/json") - 생성된 AMQP 메시지의 contentType을 "application/json"으로 설정한다.
        //                                        - 이는 메시지의 body가 어떤 형식인지를 나타내는 값이다.
        //    .build() - 설정이 완료된 AMQP 메시지를 빌드하여 반환한다.
//        Message message = MessageBuilder.withBody(json.getBytes())
//                                        .setContentType("application/json")
//                                        .build();

        // 9. 위에서 @Autowired로 생성한 RabbitTemplate를 사용하여 8에서 생성한 메시지를 전송한다.
        //    첫 번째 인자는 메시지를 발송할 Exchange 이름으로, 메시지를 받아서 어느 큐로 보낼지 결정하는 역할을 한다.
        //    두 번째 인자는 메시지를 전송할 Routing Key 이름으로, Exchange에서 메시지를 받아서 어느 큐로 보낼지 결정하는 규칙을 나타내는 값이다.
        //    세 번째 인자는 전송할 메시지 객체이다.
        //    convertAndSend() - 인자로 받은 메시지를 RabbitMQ Broker에 전송한다.
        rabbitTemplate.convertAndSend("MsgExchange", "MsgRoutingKey", canvas);
        //template.convertAndSend("/sub/meta/studyRoom/canvas/" + canvas.getMetaIdx(), canvas);
    }

    // 스터디룸 캔버스 캐릭터 이동 좌표 전달 - RabbitMQ 사용
    // @RabbitListener(queues = "MsgQueue") - RabbitMQ 메시지 큐의 "MsgQueue"라는 큐를 대상으로 메시지를 수신하는 리스너 함수임을 선언한다.
    // ackMode = "MANUAL" - ackMode는 수신한 메시지의 처리가 완료되면 RabbitMQ에 알려주는 방식을 설정하는 것으로,
    //                      "MANUAL"로 설정하면  메시지를 수신한 후 명시적으로 channel.basicAck()를 호출하여 메시지를 처리 완료했다는 신호를 보내야 한다.
    @RabbitListener(queues = "MsgQueue", ackMode = "MANUAL")
    public void receiveMessage(Message message, Channel channel, @Header(AmqpHeaders.DELIVERY_TAG) long tag) { // 1. RabbitMQ로부터 수신된 메시지를 파라미터로 받아온다.
                                                                                                               //    Channel - 메시지 큐와 통신할 수 있는 채널이다.
                                                                                                               //    @Header(AmqpHeaders.DELIVERY_TAG) - 메시지를 수신할 때 해당 메시지의 "delivery tag" 값을 가져오는 역할을 한다.
                                                                                                               //                                        "delivery tag"는 RabbitMQ가 메시지의 유일성을 보장하기 위해 각 메시지에 할당한 일련번호이다.
        try {
            // 2번은 RabbitMQConfig에서 미리 메시지 변환기를 만들어 설정해놨기에 사용할 필요가 없다.
            // 2. 1에서 파라미터로 받아온 메시지를 UTF-8로 인코딩하여 String 형태로 변환한다.
            //    message.getBody() - RabbitMQ로부터 수신된 메시지의 body를 바이트 배열 형태로 반환한다.
            //    new String(byte[] bytes, String charset) - 생성자를 사용하여 바이트 배열을 문자열로 변환하며,
            //                                               이 때 charset 인자에는 인코딩 방식(UTF-8)을 지정해주어야 한다.
//            String json = new String(message.getBody(), "UTF-8");

            // 3. 위에서 @Autowired로 생성한 ObjectMapper를 사용하여 1에서 파라미터로 받아온 JSON 형식의 메시지를 DTO로 변환한다.
            //    readValue - JSON 문자열을 Java 객체로 역직렬화하는 메소드로,
            //                첫 번째 인자로 역직렬화할 대상인 JSON 문자열을,
            //                두 번째 인자로 역직렬화할 타입인 Java 객체의 클래스를 전달받는다.
            MetaCanvasDTO canvas = objectMapper.readValue(message.getBody(), MetaCanvasDTO.class);
            // 4. 메시지가 잘 처리됬는지 체크한다.
            // 4-1. 메시지가 잘 처리됬을 경우
            //      RabbitMQ는 완료한 메시지를 삭제하고, 큐에서 제거한다.
            //      basicAck - RabbitMQ에 메시지 처리 완료를 알리는 메소드이다.
            channel.basicAck(tag, false);
            // 5. SimpMessagingTemplate를 통해 해당 path를 SUBSCRIBE하는 Client에게 DTO를 다시 전달한다.
            //    path : StompWebSocketConfig에서 설정한 enableSimpleBroker와 DTO를 전달할 경로와 3에서 변환한 DTO 값 중 방 번호가 병합된다.
            //    "/sub" + "/meta/studyRoom/canvas/" + metaIdx = "/sub/meta/studyRoom/canvas/1"
            template.convertAndSend("/sub/meta/studyRoom/canvas/" + canvas.getMetaIdx(), canvas);

            // 메시지 헤더 설정 - 헤더에 추가할 것이 있을 경우 사용한다.
//            SimpMessageHeaderAccessor headers = SimpMessageHeaderAccessor.create();
//            headers.setLeaveMutable(true);
//            headers.setNativeHeader("clear-cache", "true");
//            template.convertAndSend("/sub/meta/studyRoom/canvas/" + canvas.getMetaIdx(), canvas, headers.getMessageHeaders());
        } catch (Exception e) {
            try {
                // 4-2. 메시지가 잘 처리되지 않았을 경우
                //      RabbitMQ는 실패한 메시지를 다시 큐로 보내고, 3번째 파라미터인 requeue를 true로 설정하여 재처리한다.
                //      basicNack - RabbitMQ에 메시지 처리 실패를 알리는 메소드이다.
                channel.basicNack(tag, false, true);
            } catch (IOException ex) {
                ex.printStackTrace();
            }
        }
    }
////////////////////////////////////////////////// 카페 구역 //////////////////////////////////////////////////
    // 카페 첫 입장
    @MessageMapping(value = "/meta/cafeRoom/enter")
    public void enterCafeRoom(ChatMessageDTO message) { // 1. DTO로 채팅 정보들을 다 받아온다.
        // 2. 받아온 DTO 값 중 작성자를 가져와 참여메세지를 작성해 DTO 값 중 메세지에 저장한다.
        message.setMessage(message.getWriter() + "님이 채팅방에 참여하였습니다.");
        // 3. SimpMessagingTemplate를 통해 해당 path를 SUBSCRIBE하는 Client에게 DTO를 다시 전달한다.
        //    path : StompWebSocketConfig에서 설정한 enableSimpleBroker와 DTO를 전달할 경로와 1번에서 받아온 방 번호가 병합된다.
        //    "/sub" + "/meta/studyRoom" + metaIdx = "/sub/meta/cafeRoom/1"
        template.convertAndSend("/sub/meta/cafeRoom/" + message.getMetaIdx(), message);
    }

    // 카페 채팅
    @MessageMapping(value = "/meta/cafeRoom/message")
    public void messageCafeRoom(ChatMessageDTO message) { // 1. DTO로 채팅 정보들을 다 받아온다.
        // 2. SimpMessagingTemplate를 통해 해당 path를 SUBSCRIBE하는 Client에게 DTO를 다시 전달한다.
        //    path : StompWebSocketConfig에서 설정한 enableSimpleBroker와 DTO를 전달할 경로와 1번에서 받아온 방 번호가 병합된다.
        //    "/sub" + "/meta/studyRoom" + metaIdx = "/sub/meta/cafeRoom/1"
        template.convertAndSend("/sub/meta/cafeRoom/" + message.getMetaIdx(), message);
    }

    // 카페 퇴장
    @MessageMapping(value = "/meta/cafeRoom/exit")
    public void exitCafeRoom(ChatMessageDTO message) { // 1. DTO로 채팅 정보들을 다 받아온다.
        // 2. 받아온 DTO 값 중 작성자를 가져와 퇴장메세지를 작성해 DTO 값 중 메세지에 저장한다.
        message.setMessage(message.getWriter() + "님이 채팅방에서 탈주하였습니다.");
        // 3. SimpMessagingTemplate를 통해 해당 path를 SUBSCRIBE하는 Client에게 DTO를 다시 전달한다.
        //    path : StompWebSocketConfig에서 설정한 enableSimpleBroker와 DTO를 전달할 경로와 1번에서 받아온 방 번호가 병합된다.
        //    "/sub" + "/meta/studyRoom" + metaIdx = "/sub/meta/cafeRoom/1"
        template.convertAndSend("/sub/meta/cafeRoom/" + message.getMetaIdx(), message);
    }
////////////////////////////////////////////////// 자습실 구역 //////////////////////////////////////////////////
    // 자습실 첫 입장
    @MessageMapping(value = "/meta/oneRoom/enter")
    public void enterOneRoom(ChatMessageDTO message) { // 1. DTO로 채팅 정보들을 다 받아온다.
        // 2. 받아온 DTO 값 중 작성자를 가져와 참여메세지를 작성해 DTO 값 중 메세지에 저장한다.
        message.setMessage(message.getWriter() + "님이 채팅방에 참여하였습니다.");
        // 3. SimpMessagingTemplate를 통해 해당 path를 SUBSCRIBE하는 Client에게 DTO를 다시 전달한다.
        //    path : StompWebSocketConfig에서 설정한 enableSimpleBroker와 DTO를 전달할 경로와 1번에서 받아온 방 번호가 병합된다.
        //    "/sub" + "/meta/studyRoom" + metaIdx = "/sub/meta/oneRoom/1"
        template.convertAndSend("/sub/meta/oneRoom/" + message.getMetaIdx(), message);
    }

    // 자습실 퇴장
    @MessageMapping(value = "/meta/oneRoom/exit")
    public void exitOneRoom(ChatMessageDTO message) { // 1. DTO로 채팅 정보들을 다 받아온다.
        // 2. 받아온 DTO 값 중 작성자를 가져와 퇴장메세지를 작성해 DTO 값 중 메세지에 저장한다.
        message.setMessage(message.getWriter() + "님이 채팅방에서 탈주하였습니다.");
        // 3. SimpMessagingTemplate를 통해 해당 path를 SUBSCRIBE하는 Client에게 DTO를 다시 전달한다.
        //    path : StompWebSocketConfig에서 설정한 enableSimpleBroker와 DTO를 전달할 경로와 1번에서 받아온 방 번호가 병합된다.
        //    "/sub" + "/meta/studyRoom" + metaIdx = "/sub/meta/oneRoom/1"
        template.convertAndSend("/sub/meta/oneRoom/" + message.getMetaIdx(), message);
    }
}
