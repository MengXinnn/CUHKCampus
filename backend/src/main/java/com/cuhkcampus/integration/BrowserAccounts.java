package com.cuhkcampus.integration;

import com.cuhkcampus.agent.AgentData;
import com.cuhkcampus.common.CredentialCipher;
import org.springframework.stereotype.Service;
import java.util.*;

@Service
public class BrowserAccounts {
    private final AgentData data;
    private final CredentialCipher cipher;
    public BrowserAccounts(AgentData data,CredentialCipher cipher){this.data=data;this.cipher=cipher;}
    public Map<String,Object> get(UUID user,String provider){
        var rows=data.rows("select encrypted from browser_accounts where user_id=? and provider=?",user,provider);
        return rows.isEmpty()?new HashMap<>():new HashMap<>(data.object(cipher.decrypt((String)rows.getFirst().get("encrypted"))));
    }
    public void save(UUID user,String provider,Map<String,Object> value){data.update("insert into browser_accounts(user_id,provider,encrypted) values(?,?,?) on conflict(user_id,provider) do update set encrypted=excluded.encrypted",user,provider,cipher.encrypt(data.json(value)));}
    public void delete(UUID user,String provider){data.update("delete from browser_accounts where user_id=? and provider=?",user,provider);}
}
