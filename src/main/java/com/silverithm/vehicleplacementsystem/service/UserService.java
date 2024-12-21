package com.silverithm.vehicleplacementsystem.service;

import com.silverithm.vehicleplacementsystem.config.redis.RedisUtils;
import com.silverithm.vehicleplacementsystem.dto.FindPasswordResponse;
import com.silverithm.vehicleplacementsystem.dto.Location;
import com.silverithm.vehicleplacementsystem.dto.PasswordChangeRequest;
import com.silverithm.vehicleplacementsystem.dto.SigninResponseDTO;
import com.silverithm.vehicleplacementsystem.dto.UpdateCompanyAddressDTO;
import com.silverithm.vehicleplacementsystem.dto.UpdateCompanyNameDTO;
import com.silverithm.vehicleplacementsystem.dto.UserResponseDTO.TokenInfo;
import com.silverithm.vehicleplacementsystem.dto.UserDataDTO;
import com.silverithm.vehicleplacementsystem.dto.UserSigninDTO;
import com.silverithm.vehicleplacementsystem.entity.AppUser;
import com.silverithm.vehicleplacementsystem.exception.CustomException;
import com.silverithm.vehicleplacementsystem.jwt.JwtTokenProvider;
import com.silverithm.vehicleplacementsystem.repository.UserRepository;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.http.HttpServletRequest;
import java.security.Key;
import java.util.Collections;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.security.SecureRandom;


@Slf4j
@Service
public class UserService {

    @Autowired
    private AuthenticationManager authenticationManager;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private JwtTokenProvider jwtTokenProvider;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private EmailService emailService;
    @Autowired
    private RedisUtils redisUtils;

    @Autowired
    private GeocodingService geocodingService;

    private Key secretKey;

    public UserService(@Value("${jwt.secretKey}") String secretKey) {
        byte[] keyBytes = Decoders.BASE64.decode(secretKey);
        this.secretKey = Keys.hmacShaKeyFor(keyBytes);
    }


    public String refresh(String remoteUser) {
        return "";
    }

    public void delete(String username) {
    }

    @Transactional
    public SigninResponseDTO signin(UserSigninDTO userSigninDTO) {
        try {

            AppUser findUser = userRepository.findByEmail(userSigninDTO.getEmail())
                    .orElseThrow(() -> new CustomException("User Not Found", HttpStatus.UNPROCESSABLE_ENTITY));

            authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(userSigninDTO.getEmail(), userSigninDTO.getPassword()));

            TokenInfo tokenInfo = jwtTokenProvider.generateToken(userSigninDTO.getEmail(),
                    Collections.singleton(findUser.getUserRole()));

            findUser.update(tokenInfo.getRefreshToken());

            return new SigninResponseDTO(findUser.getId(), findUser.getUsername(), findUser.getCompanyName(),
                    findUser.getCompanyAddress(), findUser.getCompanyAddressName(),
                    tokenInfo);


        } catch (AuthenticationException e) {
            throw new CustomException("Invalid username/password supplied", HttpStatus.UNPROCESSABLE_ENTITY);
        }
    }

    public TokenInfo signup(UserDataDTO userDataDTO) throws Exception {

        TokenInfo tokenInfo = jwtTokenProvider.generateToken(userDataDTO.getName(),
                Collections.singleton(userDataDTO.getRole()));

        if (!userRepository.existsByEmail(userDataDTO.getEmail())) {
            Location companyLocation = geocodingService.getAddressCoordinates(userDataDTO.getCompanyAddress());

            AppUser user = new AppUser(userDataDTO.getName(), userDataDTO.getEmail(),
                    passwordEncoder.encode(userDataDTO.getPassword()), userDataDTO.getRole(),
                    tokenInfo.getRefreshToken(),
                    userDataDTO.getCompanyName(), companyLocation, userDataDTO.getCompanyAddress());

            userRepository.save(user);

            return tokenInfo;
        } else {
            throw new CustomException("Useremail is already in use", HttpStatus.UNPROCESSABLE_ENTITY);
        }
    }

    @Transactional
    public void logout(HttpServletRequest request) {

        String accessToken = jwtTokenProvider.resolveToken(request);
        //Access Token 검증
        if (!jwtTokenProvider.validateToken(accessToken)) {
        }

        Claims claims = Jwts.parser()
                .setSigningKey(secretKey)
                .parseClaimsJws(accessToken)
                .getBody();

        String userEmail = claims.getSubject();
        long time = claims.getExpiration().getTime() - System.currentTimeMillis();

        //Access Token blacklist에 등록하여 만료시키기
        //해당 엑세스 토큰의 남은 유효시간을 얻음
        redisUtils.setBlackList(accessToken, userEmail, time);
        //DB에 저장된 Refresh Token 제거
//        refreshTokenRepository.deleteById(userEmail);

        AppUser findUser = userRepository.findByUsername(userEmail);
        findUser.updateRefreshToken(null);
    }

    public AppUser loadUserByUsername(String username) {
        return userRepository.findByUsername(username);
    }

    public FindPasswordResponse findPassword(String email) {
        AppUser findUser = userRepository.findByEmail(email)
                .orElseThrow(() -> new CustomException("User Not Found", HttpStatus.UNPROCESSABLE_ENTITY));

        String temporaryPassword = createTemporaryPassword(findUser);

        try {
            sendTemporaryPasswordEmail(email, temporaryPassword);
            return new FindPasswordResponse("임시 비밀번호가 이메일로 전송되었습니다.");
        } catch (Exception e) {
            throw new CustomException("이메일 전송에 실패했습니다. : " + e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    private String createTemporaryPassword(AppUser user) {
        String temporaryPassword = generateRandomPassword(10);

        String encodedPassword = passwordEncoder.encode(temporaryPassword);

        user.updatePassword(encodedPassword);
        userRepository.save(user);

        return temporaryPassword;
    }

    private String generateRandomPassword(int length) {
        String chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789!@#$%^&*";
        SecureRandom secureRandom = new SecureRandom();
        StringBuilder sb = new StringBuilder();

        for (int i = 0; i < length; i++) {
            int randomIndex = secureRandom.nextInt(chars.length());
            sb.append(chars.charAt(randomIndex));
        }

        return sb.toString();
    }

    // 이메일 전송
    private void sendTemporaryPasswordEmail(String email, String temporaryPassword) {
        String subject = "실버리즘 임시 비밀번호 발급";
        String content = temporaryPassword;

        emailService.sendEmailAsync(email, subject, content);
    }

    public void changePassword(PasswordChangeRequest passwordChangeRequest) {
        AppUser findUser = userRepository.findByEmail(passwordChangeRequest.email())
                .orElseThrow(() -> new CustomException("User Not Found", HttpStatus.UNPROCESSABLE_ENTITY));

        if (!passwordEncoder.matches(passwordChangeRequest.currentPassword(), findUser.getPassword())) {
            throw new CustomException("Invalid current password", HttpStatus.UNPROCESSABLE_ENTITY);
        }

        String encodedPassword = passwordEncoder.encode(passwordChangeRequest.newPassword());
        findUser.updatePassword(encodedPassword);

        userRepository.save(findUser);
    }

    @Transactional
    public void updateCompanyName(UpdateCompanyNameDTO updateCompanyNameDTO, String userEmail) {
        AppUser findUser = userRepository.findByEmail(userEmail)
                .orElseThrow(() -> new CustomException("User Not Found", HttpStatus.UNPROCESSABLE_ENTITY));
        findUser.updateCompanyName(updateCompanyNameDTO.companyName());
    }

    @Transactional
    public void updateCompanyAddress(UpdateCompanyAddressDTO updateCompanyAddressDTO, String userEmail)
            throws Exception {
        AppUser findUser = userRepository.findByEmail(userEmail)
                .orElseThrow(() -> new CustomException("User Not Found", HttpStatus.UNPROCESSABLE_ENTITY));
        Location companyLocation = geocodingService.getAddressCoordinates(updateCompanyAddressDTO.companyAddress());
        findUser.updateCompanyAddress(companyLocation, updateCompanyAddressDTO.companyAddress());
    }
}

