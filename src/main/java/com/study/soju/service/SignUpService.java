package com.study.soju.service;

import com.study.soju.dto.MailKeyDTO;
import com.study.soju.entity.Member;
import com.study.soju.repository.MemberRepository;
import lombok.Builder;
import org.apache.commons.mail.HtmlEmail;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.Properties;

@Builder
@Service
public class SignUpService implements UserDetailsService {
    // 멤버 DB
    @Autowired
    MemberRepository memberRepository;
////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    // 아이디 중복체크 및 메일로 인증 번호 발송
    public Member.rpCheckEmailId checkEmailId(String emailId) { // 4. 파라미터로 컨트롤러에서 넘어온 아이디를 받아온다.
        // 5. 4에서 파라미터로 받아온 이메일 아이디로 유저를 조회하고, 조회된 값을 받아온다.
        Member member = memberRepository.findByEmailId(emailId);
        // 6. 5에서 조회된 값이 있는지 체크한다.
        // 6-1. 조회된 값이 있는 경우 - 중복 가입자
        if( member != null ) {
            // 6-1-1. 에러 체크 값과 에러 메세지를 DTO로 변환한다.
            Member.rpCheckEmailId rpCheckEmailId = new Member.rpCheckEmailId("0", "이미 존재하는 아이디입니다.");
            // 6-1-2. 6-1-1에서 변환된 DTO로 변환한다.
            return rpCheckEmailId;
        // 6-2. 조회된 값이 없는 경우 - 신규 가입자
        } else {
            // 7. MailKeyDTO를 사용하여 인증 번호를 생성한다.
            String mailKey = new MailKeyDTO().getKey(7, false);

            // 8. 메일 발송에 필요한 값들을 미리 설정한다.
            // 8-1. Mail Server
            String charSet = "UTF-8"; // 사용할 언어셋
            String hostSMTP = "smtp.naver.com"; // 사용할 SMTP
            String hostSMTPid = ""; // 사용할 SMTP에 해당하는 이메일
            String hostSMTPpwd = ""; // 사용할 hostSMTPid에 해당하는 PWD
            // 8-2. 메일 발신자 및 수신자
            String fromEmail = ""; // 메일 발신자 이메일 - hostSMTPid와 동일하게 작성한다.
            String fromName = "관리자"; // 메일 발신자 이름
            String mail = emailId; // 메일 수신자 이메일 - 3에서 파라미터로 받아온 아이디를 작성한다.
            // 8-3. 가장 중요한 TLS - 이것이 없으면 신뢰성 에러가 나온다
            Properties props = System.getProperties();
            props.put("mail.smtp.starttls.enable", "true");
            props.put("mail.smtp.ssl.protocols", "TLSv1.2");

            try {
                // 9. 메일 발송을 맡아줄 HtmlEmail을 생성한다.
                HtmlEmail email = new HtmlEmail(); // Email 생성
                // 10. 9에서 생성한 HtmlEmail에 8에서 미리 설정해놓은 값들과 추가로 필요한 값들을 모두 전달한다.
                email.setDebug(true); // 디버그 사용
                email.setCharset(charSet); // 언어셋 사용
                email.setHostName(hostSMTP); // SMTP 사용
                email.setSmtpPort(587);	// SMTP 포트 번호 입력
                email.setAuthentication(hostSMTPid, hostSMTPpwd); // SMTP 이메일 및 비밀번호
                email.addTo(mail); // 메일 수신자 이메일
                email.setFrom(fromEmail, fromName, charSet); // 메일 발신자 정보
                email.setSubject("[Study with me] 이메일 인증번호 발송 안내입니다."); // 메일 제목
                email.setHtmlMsg("<p>" + "[메일 인증 안내입니다.]" + "</p>" +
                                 "<p>" + "Study with me를 사용해 주셔서 감사드립니다." + "</p>" +
                                 "<p>" + "아래 인증 코드를 '인증번호'란에 입력해 주세요." + "</p>" +
                                 "<p>" + mailKey + "</p>"); // 메일 내용 - 7에서 생성한 인증 번호를 여기서 전달한다.
                // 11. 10에서 전달한 값들을 토대로 9에서 생성한 HtmlEmail을 사용하여 메일을 발송한다.
                email.send();
                // 12. 메일이 정상적으로 발송됬는지 체크한다.
                // 12-1. 메일 발송이 성공한 경우
                // 12-1-1. 4에서 파라미터로 받아온 이메일 아이디와 7에서 생성한 인증 번호를 DTO로 변환한다.
                Member.rpCheckEmailId rpCheckEmailId = new Member.rpCheckEmailId(emailId, mailKey);
                // 12-1-2. 12-1-1에서 변환된 DTO를 반환한다.
                return rpCheckEmailId;
            // 12-2. 메일 발송이 실패한 경우
            } catch (Exception e) {
                //System.out.println(e);
                // 12-2-1. 에러 체크 값과 에러 메세지를 DTO로 변환한다.
                Member.rpCheckEmailId rpCheckEmailId = new Member.rpCheckEmailId("-1", "메일 발송에 실패하였습니다.\n다시 시도해주시기 바랍니다.");
                // 12-2-2. 12-2-1에서 변환된 DTO를 반환한다.
                return rpCheckEmailId;
            }
        }
    }

    // 휴대폰 번호로 중복 가입자 체크
    public String checkPhone (String phoneNumber) { // 38. 파라미터로 컨트롤러에서 넘어온 휴대폰 번호를 받아온다.
        // 39. 38에서 파라미터로 받아온 휴대폰 번호로 유저를 조회하고, 조회된 값을 받아온다.
        Member member = memberRepository.findByPhoneNumber(phoneNumber);
        // 40. 39에서 조회된 값이 있는지 체크한다.
        // 40-1. 조회된 값이 있는 경우 - 중복 가입자
        if ( member != null ) {
            // 40-1-1. no를 반환한다.
            return "no";
        // 40-2. 조회된 값이 없는 경우 - 신규 가입자
        } else {
            // 40-2-1. yes를 반환한다.
            return "yes";
        }
    }

    // 자사 회원가입
    public Member.rpJoinMember joinMember(Member.rqJoinMember rqJoinMember, PasswordEncoder passwordEncoder) { // 3. 파라미터로 컨트롤러에서 넘어온 DTO와 비밀번호 암호화 메소드를 받아온다.
        // 4. 3에서 파라미터로 받아온 DTO를 Entity로 변환하면서 3에서 파라미터로 같이 받아온 비밀번호 암호화 메소드를 파라미터로 넘겨준다.
        Member joinMember = rqJoinMember.toEntity(passwordEncoder);
        // 8. 4에서 변환된 Entity로 유저를 저장하고, 저장한 값을 받아온다.
        Member member = memberRepository.save(joinMember);
        // 9. 8에서 저장하고 받아온 Entity를 DTO로 변환한다.
        Member.rpJoinMember rpJoinMember = new Member.rpJoinMember(member);
        // 10. 9에서 변환된 DTO를 반환한다.
        return rpJoinMember;
    }

    // 로그인 유저 닉네임 및 프로플 사진 조회
    public Member.rpNickImage memberNickImage(String emailId) { // 1. 파라미터로 컨트롤러에서 넘어온 아이디를 받아온다.
        // 2. 1에서 파라미터로 받아온 아이디로 로그인 유저를 조회하고, 조회된 값을 받아온다.
        Member member = memberRepository.findByEmailId(emailId);
        // 3. 2에서 조회된 Entity를 DTO로 변환한다.
        Member.rpNickImage rpNickImage = new Member.rpNickImage(member);
        // 4. 3에서 변환된 DTO를 반환한다.
        return rpNickImage;
    }

    // 로그인 유저 메타 프로필 작성용 조회
    public Member.rpMetaProfile metaProfile(String emailId) { // 1. 파라미터로 컨트롤러에서 넘어온 아이디를 받아온다.
        // 2. 1에서 파라미터로 받아온 아이디로 로그인 유저를 조회하고, 조회된 값을 받아온다.
        Member member = memberRepository.findByEmailId(emailId);
        // 3. 2에서 조회된 Entity를 DTO로 변환한다.
        Member.rpMetaProfile rpMetaProfile = new Member.rpMetaProfile(member);
        // 4. 3에서 변환된 DTO를 반환한다.
        return rpMetaProfile;
    }

    // 로그인시 인증 방식 - Spring Security에서 DB로 변경한다.
    @Override
    public UserDetails loadUserByUsername(String emailId) throws UsernameNotFoundException { // 3. 파라미터로 컨트롤러에서 넘어온 아이디를 받아온다.
        // 4. 3에서 파라미터로 받아온 아이디로 유저를 조회하고, 조회된 값을 받아온다.
        Member member = memberRepository.findByEmailId(emailId);
        // 5. 4에서 조회된 값이 있는지 체크한다.
        // 5-1. 조회된 값이 없는 경우
        if ( member == null ) {
            // 예외처리
            throw new UsernameNotFoundException(emailId);
        }
        // 5-2. 조회된 값이 있는 경우
        // 6. 4에서 조회된 유저를 Spring Security에서 제공하는 User에 빌더를 통해 값을 넣어준뒤 SecurityConfig로 반환한다.
        return User.builder()
                .username(member.getEmailId())
                .password(member.getPwd())
                .roles(member.getRoleName())
                .build();
    }
}
