package com.cuhkcampus.integration.blackboard;

import com.cuhkcampus.agent.AgentData;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class BlackboardLoginService {
    private final BlackboardApiConnector connector;
    private final AgentData data;
    private final String baseUrl;
    private final String chrome;
    private final com.cuhkcampus.integration.BrowserAccounts accounts;

    public BlackboardLoginService(BlackboardApiConnector connector,AgentData data,com.cuhkcampus.integration.BrowserAccounts accounts,
            @Value("${campus.blackboard.base-url:https://bb.cuhk.edu.cn}") String baseUrl,
            @Value("${campus.blackboard.chrome-binary:/Applications/Google Chrome.app/Contents/MacOS/Google Chrome}") String chrome) {
        this.connector=connector;this.data=data;this.accounts=accounts;this.baseUrl=baseUrl;this.chrome=chrome;
    }

    public Map<String,Object> account(UUID userId){
        var stored=accounts.get(userId,"BLACKBOARD");
        return Map.of("username",stored.getOrDefault("username",""),"passwordSaved",stored.containsKey("password"));
    }
    public Map<String,Object> configure(UUID userId,String username,String password){
        var stored=accounts.get(userId,"BLACKBOARD");
        if(password.isBlank()&&username.equals(stored.get("username")))password=(String)stored.getOrDefault("password","");
        if(username.isBlank()||password.isBlank())throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"请输入账号和密码");
        accounts.save(userId,"BLACKBOARD",Map.of("username",username,"password",password));
        return login(userId);
    }
    public boolean automatic(UUID userId){return accounts.get(userId,"BLACKBOARD").containsKey("password");}
    public synchronized Map<String,Object> login(UUID userId) {
        var credentials=accounts.get(userId,"BLACKBOARD");
        if(!credentials.containsKey("password"))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"请先保存 BB 账号密码");
        try(var browser=new com.cuhkcampus.integration.BrowserSession(data,chrome,true)) {
            browser.navigate(baseUrl);
            Instant deadline=Instant.now().plusSeconds(60);
            boolean usernameSubmitted=false,passwordSubmitted=false;
            while(Instant.now().isBefore(deadline)) {
                String cookie=cookieHeader(browser.cdp.call("Network.getCookies",Map.of("urls",List.of(baseUrl))));
                if(!cookie.isBlank()) {
                    try{return connector.connect(userId,cookie);}
                    catch(ResponseStatusException error){if(error.getStatusCode().value()!=401)throw error;}
                }
                {
                    String stage=String.valueOf(browser.evaluate("(()=>{if(location.hostname==='bb.cuhk.edu.cn'){const b=document.querySelector('input[value=LOGIN]');if(b){b.click();return 'redirect';}}if(location.hostname.toLowerCase()!=='sts.cuhk.edu.cn')return '';const visible=e=>e&&e.getClientRects().length;const error=[...document.querySelectorAll('.errorText')].filter(visible).map(e=>e.innerText.trim()).filter(Boolean).join(' ');if(error)return 'error:'+error;if(visible(document.getElementById('passwordInput')))return 'password';if(visible(document.getElementById('userNameInput')))return 'username';return '';})()"));
                    if(stage.startsWith("error:"))throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,stage.substring(6));
                    if(stage.equals("username")&&!usernameSubmitted){browser.evaluate("document.getElementById('userNameInput').value="+data.json(credentials.get("username"))+";document.getElementById('nextButton').click()");usernameSubmitted=true;}
                    if(stage.equals("password")&&!passwordSubmitted){browser.evaluate("document.getElementById('passwordInput').value="+data.json(credentials.get("password"))+";Login.submitLoginRequest()");passwordSubmitted=true;}
                }
                Thread.sleep(800);
            }
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"BB 自动登录未完成，请确认账号密码并检查学校认证要求");
        }catch(InterruptedException error){Thread.currentThread().interrupt();throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"登录已中断",error);}
        catch(IOException error){throw chromeUnavailable(error);}
    }

    @SuppressWarnings("unchecked")
    private String cookieHeader(Map<String,Object> response) {
        if(!(response.get("result") instanceof Map<?,?> result)||!(result.get("cookies") instanceof List<?> cookies)) return "";
        return cookies.stream().map(cookie -> (Map<String,Object>)cookie)
            .map(cookie -> cookie.get("name")+"="+cookie.get("value"))
            .collect(java.util.stream.Collectors.joining("; "));
    }

    private ResponseStatusException chromeUnavailable(Exception error) {
        return new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
            "无法启动 Chrome 登录窗口，请确认 Google Chrome 已安装",error);
    }

}
