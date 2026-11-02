package com.cuhkcampus.integration;

import com.cuhkcampus.agent.AgentData;
import com.cuhkcampus.integration.blackboard.BlackboardApiConnector;
import com.cuhkcampus.integration.outlook.OutlookSyncService;
import com.cuhkcampus.integration.chatgpt.ChatGptOAuthService;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.function.Consumer;

@Service
public class ConnectionService {
    private final AgentData data;
    private final BrowserAccounts accounts;
    private final BlackboardApiConnector blackboard;
    private final OutlookSyncService outlook;
    private final ChatGptOAuthService chatgpt;
    public ConnectionService(BrowserAccounts accounts,AgentData data,BlackboardApiConnector blackboard,OutlookSyncService outlook,ChatGptOAuthService chatgpt) {this.accounts=accounts;this.data=data;this.blackboard=blackboard;this.outlook=outlook;this.chatgpt=chatgpt;}
    public List<Map<String,Object>> list(UUID userId) {
        return data.rows("select id,provider,account_identifier,status,last_synced_at from external_connections where user_id=? order by provider",userId);
    }
    public Map<String,Object> syncOutlook(UUID userId) {
        return syncOutlook(userId,progress -> {});
    }
    public Map<String,Object> syncOutlook(UUID userId,Consumer<SyncProgress> progress) {
        return outlook.sync(userId,progress);
    }
    public void disconnect(UUID userId,String provider) {
        String source=provider.toUpperCase(Locale.ROOT);
        if(source.equals("CHATGPT")) {chatgpt.disconnect(userId);return;}
        if(source.equals("BLACKBOARD")) {blackboard.disconnect(userId);accounts.delete(userId,"BLACKBOARD");}
        data.update("delete from external_connections where user_id=? and provider=?",userId,source);
    }
}
