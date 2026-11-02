package com.cuhkcampus.calendar;

import com.cuhkcampus.task.CampusTask;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CalendarServiceTest {
    final UUID user=UUID.randomUUID();
    final EntityManager em=mock(EntityManager.class);
    final CalendarService service=new CalendarService(null,null);
    @BeforeEach @SuppressWarnings("unchecked") void setup(){
        service.em=em;
        TypedQuery<CalendarEvent> query=mock(TypedQuery.class);
        when(em.createQuery(anyString(),eq(CalendarEvent.class))).thenReturn(query);
        when(query.setParameter(eq("user"),eq(user))).thenReturn(query);
        when(query.getResultList()).thenReturn(List.of());
    }
    CampusTask task(int minutes){var t=new CampusTask();t.userId=user;t.title="复习";t.estimatedMinutes=minutes;when(em.find(CampusTask.class,t.id)).thenReturn(t);return t;}
    Instant at(String time){return Instant.parse("2026-10-29T"+time+":00Z");}
    @Test void schedulesAroundExistingClass(){
        var task=task(60);var existing=new CalendarEvent();existing.startsAt=at("09:30");existing.endsAt=at("10:30");
        var query=em.createQuery("",CalendarEvent.class);when(query.getResultList()).thenReturn(List.of(existing));
        var planned=service.planStudySessions(user,List.of(task.id),at("09:00"),at("12:00"));
        assertEquals(2,planned.size());assertEquals(at("09:00"),planned.get(0).startsAt);assertEquals(at("09:30"),planned.get(0).endsAt);
        assertEquals(at("10:30"),planned.get(1).startsAt);assertEquals(at("11:00"),planned.get(1).endsAt);
        verify(em,times(2)).persist(any(CalendarEvent.class));
    }
    @Test void insertsTenMinuteBreakAfterFiftyMinutes(){
        var task=task(70);var planned=service.planStudySessions(user,List.of(task.id),at("09:00"),at("12:00"));
        assertEquals(at("09:50"),planned.get(0).endsAt);assertEquals(at("10:00"),planned.get(1).startsAt);assertEquals(at("10:20"),planned.get(1).endsAt);
    }
    @Test void insufficientTimeDoesNotSavePartialPlan(){
        var task=task(90);
        assertThrows(ResponseStatusException.class,()->service.planStudySessions(user,List.of(task.id),at("09:00"),at("09:30")));
        verify(em,never()).persist(any());
    }
    @Test void doesNotScheduleAnotherUsersTask(){
        var task=task(30);task.userId=UUID.randomUUID();
        assertThrows(ResponseStatusException.class,()->service.planStudySessions(user,List.of(task.id),at("09:00"),at("11:00")));
        verify(em,never()).persist(any());
    }
}
