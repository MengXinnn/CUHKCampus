package com.cuhkcampus.agent;

import com.cuhkcampus.common.CredentialCipher;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.util.*;

@Service
public class AiSettingsService {
    public record Config(String mode,String baseUrl,String model,boolean hasKey) {}
    private record Keys(String mode,String baseUrl,String model,String apiKey) {}

    private final AgentData data;
    private final CredentialCipher cipher;
    private final RestClient http;
    private final Keys agent=new Keys("aiMode","aiBaseUrl","aiModel","aiApiKey");
    private final Keys translation=new Keys("translationAiMode","translationAiBaseUrl","translationAiModel","translationAiApiKey");

    public AiSettingsService(AgentData data,CredentialCipher cipher,RestClient.Builder http) {
        this.data=data;this.cipher=cipher;this.http=http.build();
    }

    public Map<String,Object> all(UUID userId) {
        return Map.of("agent",config(userId,"agent"),"translation",config(userId,"translation"));
    }

    public Config config(UUID userId,String kind) {
        Keys keys=keys(kind);
        Map<String,Object> preferences=preferences(userId);
        String mode=preferences.get(keys.mode()) instanceof String value&&!value.isBlank()?value:"API_KEY";
        String base=preferences.get(keys.baseUrl()) instanceof String value?value:"";
        String model=preferences.get(keys.model()) instanceof String value?value:"";
        boolean hasKey=preferences.get(keys.apiKey()) instanceof String value&&!value.isBlank();
        return new Config(mode,base,model,hasKey);
    }

    public Config save(UUID userId,String kind,String mode,String baseUrl,String model,String apiKey) {
        Keys keys=keys(kind);
        String selectedMode=mode==null||mode.isBlank()?"API_KEY":mode;
        var values=new LinkedHashMap<String,Object>();
        values.put(keys.mode(),selectedMode);
        values.put(keys.model(),model==null?"":model.strip());
        if("API_KEY".equals(selectedMode)) values.put(keys.baseUrl(),baseUrl==null?"":baseUrl.strip().replaceAll("/+$",""));
        if("API_KEY".equals(selectedMode)&&apiKey!=null&&!apiKey.isBlank()) values.put(keys.apiKey(),cipher.encrypt(apiKey.strip()));
        data.update("update users set preferences=preferences||?::jsonb where id=?",data.json(values),userId);
        return config(userId,kind);
    }

    public List<Map<String,String>> models(UUID userId,String kind,String baseUrl,String apiKey) {
        String key=requestKey(userId,kind,apiKey);
        Map<String,Object> response=request(() -> data.object(http.get().uri(URI.create(endpoint(baseUrl,"/models")))
                .header("Authorization","Bearer "+key).retrieve().body(String.class)));
        if(!(response.get("data") instanceof List<?> items)) return List.of();
        return items.stream().map(item -> (Map<?,?>)item).map(item -> {
            String id=String.valueOf(item.get("id"));
            Object name=item.get("name");
            return Map.of("id",id,"name",name==null?id:String.valueOf(name));
        }).toList();
    }

    public Map<String,Object> test(UUID userId,String kind,String baseUrl,String model,String apiKey) {
        String key=requestKey(userId,kind,apiKey);
        var payload=Map.of("model",model,"messages",List.of(Map.of("role","user","content","Reply OK")),
                "max_tokens",1,"stream",false);
        long started=System.nanoTime();
        request(() -> http.post().uri(URI.create(endpoint(baseUrl,"/chat/completions")))
                .header("Authorization","Bearer "+key).contentType(MediaType.APPLICATION_JSON)
                .body(data.json(payload)).retrieve().body(String.class));
        return Map.of("ok",true,"latencyMs",(System.nanoTime()-started)/1_000_000);
    }

    public String apiKey(UUID userId,String kind) {
        Keys keys=keys(kind);
        Map<String,Object> preferences=preferences(userId);
        if(!(preferences.get(keys.apiKey()) instanceof String encrypted)||encrypted.isBlank())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"请在设置中填写 API Key");
        return cipher.decrypt(encrypted);
    }

    private Keys keys(String kind) {
        if("agent".equals(kind)) return agent;
        if("translation".equals(kind)) return translation;
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"未知模型配置");
    }

    private Map<String,Object> preferences(UUID userId) {
        return data.object(data.jdbc.sql("select preferences::text from users where id=?").param(userId).query(String.class).single());
    }

    private String requestKey(UUID userId,String kind,String requested) {
        if(requested!=null&&!requested.isBlank()) return requested.strip();
        return apiKey(userId,kind);
    }

    private String endpoint(String base,String path) {
        if(base==null||base.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"请输入服务地址");
        return base.strip().replaceAll("/+$","")+path;
    }

    private <T> T request(java.util.function.Supplier<T> operation) {
        try {return operation.get();}
        catch(RestClientResponseException error) {
            String message=error.getResponseBodyAsString();
            try {
                Map<String,Object> payload=data.object(message);
                if(payload.get("error") instanceof Map<?,?> detail&&detail.get("message")!=null) message=String.valueOf(detail.get("message"));
                else if(payload.get("message")!=null) message=String.valueOf(payload.get("message"));
            } catch(RuntimeException ignored) {}
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"模型服务请求失败（HTTP "+error.getStatusCode().value()+"）："+message);
        }
    }
}
