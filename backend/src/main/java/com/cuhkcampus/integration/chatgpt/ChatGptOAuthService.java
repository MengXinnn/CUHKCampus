package com.cuhkcampus.integration.chatgpt;

import com.cuhkcampus.agent.AgentData;
import com.cuhkcampus.common.CredentialCipher;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriComponentsBuilder;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.*;
import java.time.Instant;
import java.util.*;

/** Official public-client OAuth flow. Tokens remain encrypted on the local server. */
@Service
public class ChatGptOAuthService {
    private static final String ISSUER="https://auth.openai.com";
    private static final String RESOURCE="https://api.openai.com/v1";
    private static final String SCOPE="openid profile email offline_access resource.invoke chatgpt.tokens.use.direct";
    private static final String CALLBACK="http://127.0.0.1:8080/api/v1/connections/chatgpt/callback";
    private static final String PLAN="chatgpt.tokens.use.direct";
    private final AgentData data;
    private final CredentialCipher cipher;
    private final Path hostFile;
    private final RestClient http=RestClient.create();
    private final Map<String,Pending> pending=new HashMap<>();
    private Map<String,Object> discovery;
    private String hostId;
    private record Pending(UUID userId,String clientId,String subject,String nonce,String verifier,Instant createdAt) {}
    public ChatGptOAuthService(AgentData data,CredentialCipher cipher,
            @Value("${campus.chatgpt-host-id-file:./data/chatgpt-host-id}") String hostFile){
        this.data=data;this.cipher=cipher;this.hostFile=Path.of(hostFile).toAbsolutePath();
    }
    public synchronized String hostId(){
        if(hostId!=null)return hostId;
        try {
            if(Files.exists(hostFile))hostId=Files.readString(hostFile).strip();
            else {
                Files.createDirectories(hostFile.getParent());
                hostId="urn:uuid:"+UUID.randomUUID();
                Files.createFile(hostFile,PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
                Files.writeString(hostFile,hostId,StandardOpenOption.WRITE);
            }
            return hostId;
        }catch(Exception e){throw new IllegalStateException("无法保存 ChatGPT 主机标识",e);}
    }
    private Map<String,Object> discovery(){
        if(discovery==null){
            discovery=data.object(http.get().uri(ISSUER+"/.well-known/openid-configuration").retrieve().body(String.class));
            if(!ISSUER.equals(discovery.get("issuer")))throw new IllegalStateException("OpenAI OAuth issuer 不匹配");
        }
        return discovery;
    }
    private String endpoint(String name){return (String)discovery().get(name);}
    private String random(){byte[] bytes=new byte[32];new SecureRandom().nextBytes(bytes);return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);}
    private String challenge(String verifier){try{return Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));}catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
    @SuppressWarnings("unchecked")
    private Map<String,Object> registration(UUID userId){
        var rows=data.rows("select credentials from external_connections where user_id=? and provider='CHATGPT'",userId);
        if(rows.isEmpty())return new LinkedHashMap<>();
        var encrypted=(Map<String,Object>)rows.get(0).get("credentials");
        return data.object(cipher.decrypt((String)encrypted.get("encrypted")));
    }
    private boolean planEnabled(Map<String,Object> registration){return scopes(registration).contains(PLAN);}
    @SuppressWarnings("unchecked") private List<String> scopes(Map<String,Object> registration){return (List<String>)registration.getOrDefault("scopes",List.of());}
    private boolean connected(Map<String,Object> registration){return registration.get("access_token")!=null;}
    private void save(UUID userId,Map<String,Object> selected){
        var credentials=data.json(Map.of("encrypted",cipher.encrypt(data.json(selected))));
        String account=(String)selected.get("email");if(account==null)account=(String)selected.get("subject");
        data.update("insert into external_connections(id,user_id,provider,account_identifier,credentials,status) values(?,?,'CHATGPT',?,?::jsonb,?) on conflict(user_id,provider) do update set account_identifier=excluded.account_identifier,credentials=excluded.credentials,status=excluded.status",UUID.randomUUID(),userId,account,credentials,connected(selected)?"CONNECTED":"DISCONNECTED");
    }
    public synchronized String authorize(UUID userId){
        var selected=registration(userId);
        String issued=selected.isEmpty()?"dynamic_agent_client":(String)selected.get("client_id");
        String state=random(),nonce=random(),verifier=random();
        pending.entrySet().removeIf(e->e.getValue().createdAt().isBefore(Instant.now().minusSeconds(600)));
        pending.put(state,new Pending(userId,issued,(String)selected.get("subject"),nonce,verifier,Instant.now()));
        var uri=UriComponentsBuilder.fromUriString(endpoint("authorization_endpoint"))
            .queryParam("client_id",issued).queryParam("ext_agent_host_id",hostId())
            .queryParam("response_type","code").queryParam("redirect_uri",CALLBACK).queryParam("scope",SCOPE).queryParam("resource",RESOURCE)
            .queryParam("state",state).queryParam("nonce",nonce).queryParam("code_challenge_method","S256").queryParam("code_challenge",challenge(verifier));
        if(selected.isEmpty())uri.queryParam("agent_name_hint","CUHKCampus");
        else {if(selected.get("id_token")!=null)uri.queryParam("id_token_hint",selected.get("id_token"));if(selected.get("email")!=null)uri.queryParam("login_hint",selected.get("email"));}
        return uri.build().encode().toUriString();
    }
    public synchronized void callback(String state,String code,String returnedClientId,String error){
        var attempt=pending.remove(state);
        if(attempt==null || attempt.createdAt().isBefore(Instant.now().minusSeconds(600)))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"ChatGPT 授权会话无效");
        if(error!=null)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"ChatGPT 授权未完成："+error);
        if(code==null || code.isBlank())throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"ChatGPT 授权未返回 code");
        boolean initial="dynamic_agent_client".equals(attempt.clientId());
        String issued=initial?returnedClientId:attempt.clientId();
        if(issued==null || issued.isBlank() || "dynamic_agent_client".equals(issued) || (!initial && returnedClientId!=null && !issued.equals(returnedClientId)))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"ChatGPT 注册 client_id 不匹配");
        var form=new LinkedMultiValueMap<String,String>();form.add("grant_type","authorization_code");form.add("client_id",issued);form.add("code",code);form.add("code_verifier",attempt.verifier());form.add("redirect_uri",CALLBACK);form.add("resource",RESOURCE);
        if(initial){var registration=registration(attempt.userId());if(!registration.isEmpty())throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"已有 ChatGPT 注册，请重新开始登录");registration.put("client_id",issued);save(attempt.userId(),registration);}
        var tokens=token(form);Jwt identity=validateIdentity((String)tokens.get("id_token"),issued,attempt.nonce());
        if(attempt.subject()!=null && !attempt.subject().equals(identity.getSubject()))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"ChatGPT 账号与所选注册不匹配");
        var selected=registration(attempt.userId());
        if(selected.get("subject")!=null && (!selected.get("subject").equals(identity.getSubject()) || !selected.get("client_id").equals(issued)))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"ChatGPT 账号身份不匹配");
        selected.put("client_id",issued);selected.put("subject",identity.getSubject());selected.put("issuer",identity.getIssuer().toString());selected.put("email",identity.getClaimAsString("email"));
        updateTokens(selected,tokens);save(attempt.userId(),selected);
    }
    private Jwt validateIdentity(String idToken,String clientId,String nonce){
        var decoder=NimbusJwtDecoder.withJwkSetUri(endpoint("jwks_uri")).jwsAlgorithm(SignatureAlgorithm.RS256).build();
        OAuth2TokenValidator<Jwt> audience=jwt->jwt.getAudience().contains(clientId)?OAuth2TokenValidatorResult.success():OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token","ID token audience 不匹配",null));
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer(ISSUER),audience));
        Jwt jwt=decoder.decode(idToken);
        if(jwt.getExpiresAt()==null || jwt.getSubject()==null || (nonce!=null && !MessageDigest.isEqual(nonce.getBytes(StandardCharsets.UTF_8),String.valueOf(jwt.getClaimAsString("nonce")).getBytes(StandardCharsets.UTF_8))))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"ChatGPT ID token 无效");
        return jwt;
    }
    private Map<String,Object> token(LinkedMultiValueMap<String,String> form){
        try{return data.object(http.post().uri(endpoint("token_endpoint")).contentType(MediaType.APPLICATION_FORM_URLENCODED).body(form).retrieve().body(String.class));}
        catch(RestClientResponseException e){throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"ChatGPT OAuth 换取凭据失败（HTTP "+e.getStatusCode().value()+"）");}
    }
    private void updateTokens(Map<String,Object> selected,Map<String,Object> tokens){
        selected.put("access_token",tokens.get("access_token"));selected.put("refresh_token",tokens.get("refresh_token"));
        if(tokens.get("id_token")!=null)selected.put("id_token",tokens.get("id_token"));
        if(tokens.get("scope")!=null){String scope=(String)tokens.get("scope");selected.put("scopes",scope.isBlank()?List.of():List.of(scope.strip().split("\\s+")));}
        selected.put("expires_at",Instant.now().plusSeconds(((Number)tokens.get("expires_in")).longValue()).toString());
    }
    public synchronized String accessToken(UUID userId){
        var selected=registration(userId);
        if(!connected(selected))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"请重新 Continue with ChatGPT");
        if(!planEnabled(selected))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"已登录 ChatGPT，但未授权使用 ChatGPT 计划");
        if(!Instant.parse((String)selected.get("expires_at")).isAfter(Instant.now())){
            var form=new LinkedMultiValueMap<String,String>();form.add("grant_type","refresh_token");form.add("client_id",(String)selected.get("client_id"));form.add("refresh_token",(String)selected.get("refresh_token"));form.add("resource",RESOURCE);
            var refreshed=token(form);
            if(refreshed.get("id_token")!=null){var identity=validateIdentity((String)refreshed.get("id_token"),(String)selected.get("client_id"),null);if(!selected.get("subject").equals(identity.getSubject()))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"刷新后的 ChatGPT 账号身份不匹配");}
            updateTokens(selected,refreshed);save(userId,selected);
        }
        if(!planEnabled(selected))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"ChatGPT 计划权限未启用");
        return (String)selected.get("access_token");
    }
    public synchronized Map<String,Object> status(UUID userId){
        var selected=registration(userId);var response=new LinkedHashMap<String,Object>();
        response.put("connected",connected(selected));response.put("planEnabled",planEnabled(selected));response.put("account",selected.get("email"));return response;
    }
    @SuppressWarnings("unchecked") public Map<String,Object> models(UUID userId){
        var response=data.object(http.get().uri(RESOURCE+"/models").headers(h->h.setBearerAuth(accessToken(userId))).retrieve().body(String.class));
        var catalog=(List<Map<String,Object>>)response.get("models");
        return Map.of("models",catalog.stream().filter(m->"list".equals(m.get("visibility"))).map(m->Map.of("id",m.get("slug"),"name",m.get("display_name"))).toList());
    }
    public synchronized void disconnect(UUID userId){
        var selected=registration(userId);
        if(selected.get("refresh_token")!=null){var form=new LinkedMultiValueMap<String,String>();form.add("token",(String)selected.get("refresh_token"));form.add("token_type_hint","refresh_token");form.add("client_id",(String)selected.get("client_id"));http.post().uri(endpoint("revocation_endpoint")).contentType(MediaType.APPLICATION_FORM_URLENCODED).body(form).retrieve().toBodilessEntity();}
        for(String field:List.of("access_token","refresh_token","id_token","expires_at","scopes"))selected.remove(field);
        save(userId,selected);
    }
}
