package com.study.soju.controller;

import com.study.soju.dto.ChatMessageDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;

import java.util.concurrent.*;

@Controller
@RequiredArgsConstructor
public class StompChatController {
    // @MessageMapping으로 받아온 메시지를 다시 클라이언트로 전달해주는 SimpMessagingTemplate
    @Autowired
    SimpMessagingTemplate template; // 특정 Broker로 메세지를 전달한다.

    // 메시지를 보낼 때 퇴장 메시지와 재입장 메시지를 관리하기 위한 ConcurrentHashMap
    ConcurrentHashMap<String, ChatMessageDTO> metaMessageMap = new ConcurrentHashMap<>();

    // Client에서 전송한 SEND 요청을 처리
    // @MessageMapping - 클라이언트에서 요청을 보낸 URI 에 대응하는 메소드로 연결을 해주는 역할을 한다.
    // StompWebSocketConfig에서 설정한 applicationDestinationPrefixes와 @MessageMapping 경로가 자동으로 병합된다.
    // "/pub" + "/meta/studyRoom/enter" = "/pub/meta/studyRoom/enter"
////////////////////////////////////////////////// 스터디룸 구역 //////////////////////////////////////////////////
    // 스터디룸 입장
    @MessageMapping(value = "/meta/studyRoom/enter")
    public void enterStudyRoom(ChatMessageDTO message) { // 1. DTO로 채팅 정보들을 다 받아온다.
        // 2. 1에서 받아온 DTO 값 중 작성자를 가져와 참여메세지를 작성해 DTO 값 중 메세지에 저장한다.
        message.setMessage(message.getWriter() + "님이 채팅방에 참여하였습니다.");
        // 3. 1에서 받아온 DTO 값 중 작성자를 가져와 참여자로 저장한다.
        message.setParticipant(message.getWriter());
        // 4. SimpMessagingTemplate를 통해 해당 path를 SUBSCRIBE하는 Client에게 DTO를 다시 전달한다.
        //    path : StompWebSocketConfig에서 설정한 enableSimpleBroker와 DTO를 전달할 경로와 1에서 받아온 방 번호가 병합된다.
        //    "/sub" + "/meta/studyRoom" + metaIdx = "/sub/meta/studyRoom/1"
        template.convertAndSend("/sub/meta/studyRoom/" + message.getMetaIdx(), message);
    }

    @MessageMapping(value = "/meta/studyRoom/reenter")
    public void reEnterStudyRoom(ChatMessageDTO message, SimpMessageHeaderAccessor accessor) { // 1. DTO로 채팅 정보들을 다 받아온다.
        String metaIdx = (String) accessor.getSessionAttributes().get("metaIdx");
        // 이전의 퇴장 메시지 삭제
        ChatMessageDTO exitMessage = metaMessageMap.get(metaIdx + "_exit");
        if (exitMessage != null) {
            metaMessageMap.remove(metaIdx + "_exit");
        }

        template.convertAndSend("/sub/meta/studyRoom/" + message.getMetaIdx(), message);
    }

    // 스터디룸 채팅
    @MessageMapping(value = "/meta/studyRoom/message")
    public void messageStudyRoom(ChatMessageDTO message) { // 1. DTO로 채팅 정보들을 다 받아온다.
        // 2. SimpMessagingTemplate를 통해 해당 path를 SUBSCRIBE하는 Client에게 DTO를 다시 전달한다.
        //    path : StompWebSocketConfig에서 설정한 enableSimpleBroker와 DTO를 전달할 경로와 1에서 받아온 방 번호가 병합된다.
        //    "/sub" + "/meta/studyRoom" + metaIdx = "/sub/meta/studyRoom/1"
        template.convertAndSend("/sub/meta/studyRoom/" + message.getMetaIdx(), message);
    }

    // 스터디룸 퇴장
    @MessageMapping(value = "/meta/studyRoom/exit")
    // Future - Future 인터페이스는 java5부터 java.util.concurrency 패키지에서 비동기의 결과값을 받는 용도로 사용했지만 비동기의 결과값을 조합하거나, error를 핸들링할 수가 없었다.
    // CompletionStage - Java 8에서 추가된 인터페이스 중 하나로, 비동기식 계산 결과를 다루기 위한 일종의 통합 API이다.
    //                   CompletableFuture 클래스와 함께 사용되어, 비동기 작업을 수행하고 작업 결과를 처리하는 기능을 제공한다.
    // CompletableFuture - Java 8에서 추가된 클래스 중 하나로, 비동기 작업을 수행하고 해당 작업의 결과를 처리하는 기능을 제공한다.
    // <Void> - runAsync는 반환 값이 없으므로 Void 타입이다.
    public CompletableFuture<Void> exitStudyRoom(ChatMessageDTO message) { // 1. 클라이언트로부터 전송된 퇴장 정보들을 DTO로 받아온다.
        // 2. 받아온 DTO 값 중 작성자를 가져와 퇴장 메시지를 작성해 DTO 값 중 메시지에 저장한다.
        message.setMessage(message.getWriter() + "님이 채팅방에서 탈주하였습니다.");
        // 3. 1에서 받아온 DTO 값 중 작성자를 가져와 퇴장자로 저장한다.
        message.setExit(message.getWriter());
        // 4. 1에서 받아온 DTO 값 중 참가중인 인원을 가져와 1을 감소한뒤 다시 참가중인 인원에 저장한다.
        message.setMetaRecruitingPersonnel(message.getMetaRecruitingPersonnel() - 1);

        // 5. 1에서 받아온 DTO 값 중 방 번호와 퇴장을 의미하는 문자를 조합하여 키로 사용하고, 1에서 받아온 DTO를 값으로 사용하여 Map에 추가한다.
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
                    //        path : StompWebSocketConfig에서 설정한 enableSimpleBroker와 DTO를 전달할 경로와 1에서 받아온 방 번호가 병합된다.
                    //        "/sub" + "/meta/studyRoom" + metaIdx = "/sub/meta/studyRoom/1"
                    template.convertAndSend("/sub/meta/studyRoom/" + message.getMetaIdx(), message);
                    // 8-1-2. 5에서 Map에 추가한 키에 해당하는 DTO를 삭제한다.
                    metaMessageMap.remove(message.getMetaIdx() + "_exit");
                // 8-2. 퇴장 메시지가 존재하지 않는 경우 - 재입장(새로고침)
                } else {
                    // 8-2-1. 퇴장한 것이 아니기에 더 이상 작업할 것이 없다.
                }
            } catch (InterruptedException e) { // 스레드를 중지하거나 중단시킬 때 발생할 수 있는 예외
                throw new RuntimeException(e);
            }
        });
    }
////////////////////////////////////////////////// 카페 구역 //////////////////////////////////////////////////
    // 카페 입장
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
    // 자습실 입장
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
