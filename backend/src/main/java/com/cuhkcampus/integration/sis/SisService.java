package com.cuhkcampus.integration.sis;

import com.cuhkcampus.agent.AgentData;
import com.cuhkcampus.integration.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Pattern;
import org.jsoup.Jsoup;

@Service
public class SisService {
    private final AgentData data; private final BrowserAccounts accounts; private final String chrome;private final TransactionTemplate transaction;
    public SisService(AgentData data,BrowserAccounts accounts,TransactionTemplate transaction,@Value("${campus.blackboard.chrome-binary:/Applications/Google Chrome.app/Contents/MacOS/Google Chrome}")String chrome){this.data=data;this.accounts=accounts;this.chrome=chrome;this.transaction=transaction;}
    public Map<String,Object> sync(UUID user){
        var credentials=accounts.get(user,"BLACKBOARD");
        if(!credentials.containsKey("password"))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"请先在数据连接中保存学校账号密码");
        try(var browser=new BrowserSession(data,chrome,true)){
            browser.navigate("https://sis.cuhk.edu.cn/sso/login.html");
            boolean username=false,password=false;boolean authenticated=false;
            for(int i=0;i<80;i++){
                String stage=String.valueOf(browser.evaluate("(()=>{const v=e=>e&&e.getClientRects().length;if(document.getElementById('pthdr2logout'))return 'ready';if(v(document.getElementById('passwordInput')))return 'password';if(v(document.getElementById('userNameInput')))return 'username';const e=document.querySelector('.errorText');return v(e)?'error:'+e.innerText:'';})()"));
                if(stage.startsWith("error:"))throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,stage.substring(6));
                if(stage.equals("ready")){authenticated=true;break;}
                if(stage.equals("username")&&!username){browser.evaluate("document.getElementById('userNameInput').value="+data.json(credentials.get("username"))+";document.getElementById('nextButton').click()");username=true;}
                if(stage.equals("password")&&!password){browser.evaluate("document.getElementById('passwordInput').value="+data.json(credentials.get("password"))+";Login.submitLoginRequest()");password=true;}
                Thread.sleep(750);
            }
            if(!authenticated)throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"SIS 登录未完成，请检查学校认证要求");
            browser.navigate("https://sis.cuhk.edu.cn/psc/csprd/EMPLOYEE/HRMS/c/SA_LEARNER_SERVICES.SSR_SSENRL_SCHD_W.GBL");
            boolean list=false;String html=null;
            for(int i=0;i<40;i++){
                String state=String.valueOf(browser.evaluate("(()=>{if(document.querySelector('table[id^=\"CLASS_MTG_VW\"]'))return 'ready';const l=document.querySelector('input[type=radio][value=L]');if(l&&!l.checked)return 'weekly';return '';})()"));
                if(state.equals("ready")){html=String.valueOf(browser.evaluate("document.documentElement.outerHTML"));break;}
                if(state.equals("weekly")&&!list){browser.evaluate("document.querySelector('input[type=radio][value=L]').click()");list=true;}
                Thread.sleep(500);
            }
            if(html==null)throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"SIS 未返回课程列表");
            var events=parse(html);
            transaction.executeWithoutResult(status->{
                data.update("delete from calendar_events where user_id=? and event_type='SIS'",user);
                for(var event:events)data.update("insert into calendar_events(id,user_id,event_type,title,starts_at,ends_at,location) values(?,?,'SIS',?,?,?,?)",UUID.randomUUID(),user,event.get("title"),event.get("startsAt"),event.get("endsAt"),event.get("location"));
            });
            return Map.of("count",events.size());
        }catch(InterruptedException e){Thread.currentThread().interrupt();throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"SIS 读取已中断",e);}
        catch(java.io.IOException e){throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"无法启动 SIS 登录浏览器",e);}
    }
    public static List<Map<String,Object>> parse(String html){
        var result=new ArrayList<Map<String,Object>>();String title="";
        var dateFormat=DateTimeFormatter.ofPattern("MM/dd/uuuu");var timeFormat=DateTimeFormatter.ofPattern("h:mma",Locale.US);
        var times=Pattern.compile("(\\d{1,2}:\\d{2}[AP]M)\\s*-\\s*(\\d{1,2}:\\d{2}[AP]M)");
        for(var element:Jsoup.parse(html).select(".PAGROUPDIVIDER,table[id^=CLASS_MTG_VW]")){
            if(element.hasClass("PAGROUPDIVIDER")){title=element.text();continue;}
            for(var row:element.select("tr")){
                var cells=row.select("td");if(cells.isEmpty())continue;
                String schedule=cells.get(3).text();var matcher=times.matcher(schedule);
                if(!matcher.find())throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"SIS 课程时间无法读取："+title);
                var dates=cells.get(6).text().split(" - ");var start=LocalDate.parse(dates[0],dateFormat);var end=LocalDate.parse(dates[1],dateFormat);
                for(var day=start;!day.isAfter(end);day=day.plusDays(1)){
                    String weekday="星期"+"一二三四五六日".charAt(day.getDayOfWeek().getValue()-1);
                    if(!schedule.contains(weekday))continue;
                    result.add(Map.of("title",title+" · "+cells.get(1).text(),"startsAt",day.atTime(LocalTime.parse(matcher.group(1),timeFormat)).atZone(ZoneId.of("Asia/Shanghai")).toInstant(),"endsAt",day.atTime(LocalTime.parse(matcher.group(2),timeFormat)).atZone(ZoneId.of("Asia/Shanghai")).toInstant(),"location",cells.get(4).text()));
                }
            }
        }
        return result;
    }
}
