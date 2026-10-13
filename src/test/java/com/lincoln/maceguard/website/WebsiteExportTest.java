package com.lincoln.maceguard.website;

import com.lincoln.maceguard.warzone.config.WarzoneConfig;
import com.lincoln.maceguard.warzone.config.WarzoneControlConfig;
import com.lincoln.maceguard.warzone.message.WarzoneMessageService;
import com.lincoln.maceguard.warzone.rotation.*;
import com.lincoln.maceguard.warzone.runtime.WarzoneRuntime;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WebsiteExportTest {
    private static final String ITEMS="items",NAME="name",PRIVATE_VALUE="private";
    private final Map<String,Object> rotation=Map.of("kind","warzone",ITEMS,List.of(Map.of("id","current-meta","type","kit",NAME,"Vanilla")));
    @Test void exactJavaSigningMatchesIndependentNodeHmacFixture() {
        var target=WebsiteSignedExport.endpoint("https://enthusia.info/api/community/ingest");
        var request=WebsiteSignedExport.request(target,"k".repeat(32),"{}",1791820800000L,"0123456789abcdef");
        assertEquals("49b1bbc4d5bd41b26ba29ab4e7839f3bc4d8ab4b17c8dee2c2b854f8262a3b4f",request.headers().firstValue("x-enthusia-signature").orElseThrow());
        assertEquals("POST",request.method());
        assertThrows(IllegalArgumentException.class,()->WebsiteSignedExport.request(target,"short","{}",1,"0123456789abcdef"));
        assertThrows(IllegalArgumentException.class,()->WebsiteSignedExport.request(target,"k".repeat(32),"{}",1,"invalid\nnonce"));
    }
    @Test void destinationsCannotSmuggleCredentialsRedirectTargetsOrOtherRoutes() {
        for(String target:List.of("http://enthusia.info/api/community/ingest","https://u:p@enthusia.info/api/community/ingest",
                "https://enthusia.info/api/community/ingest?token=private","https://enthusia.info/api/community/ingest#fragment","https://enthusia.info/other"))
            assertThrows(IllegalArgumentException.class,()->WebsiteSignedExport.endpoint(target));
    }
    @Test void missingInvalidAndEmptyDuelExportsAreDistinctAndNeverLeakExtraFields() {
        assertFalse(PublicWarzoneSnapshot.withLeaders(rotation,null).toString().contains("type=leader"));
        assertTrue(PublicWarzoneSnapshot.withLeaders(rotation,null).toString().contains("unavailable"));
        assertTrue(PublicWarzoneSnapshot.withLeaders(rotation,List.of()).toString().contains("No counted"));
        var row=Map.of("id","00000000-0000-0000-0000-000000000001",NAME,"Player","wins",1.5,"ip",PRIVATE_VALUE);
        assertTrue(PublicWarzoneSnapshot.withLeaders(rotation,List.of(row)).toString().contains("unavailable"));
        var valid=Map.of("id","00000000-0000-0000-0000-000000000001",NAME,"Player","wins",7,"opponent",PRIVATE_VALUE);
        var result=PublicWarzoneSnapshot.withLeaders(rotation,List.of(valid));
        assertTrue(result.toString().contains("wins=7"));assertFalse(result.toString().contains(PRIVATE_VALUE));
        assertThrows(UnsupportedOperationException.class,()->result.remove(ITEMS));
        var copied=(Map<?,?>)((List<?>)result.get(ITEMS)).getFirst();
        assertThrows(UnsupportedOperationException.class,()->copied.remove(NAME));
    }
    @Test void weeksUseUtcMondayAndNotTheRotationTimezone() {
        assertEquals(Instant.parse("2026-10-05T00:00:00Z").toEpochMilli(),PublicWarzoneSnapshot.weekStart(Instant.parse("2026-10-11T23:59:59Z").toEpochMilli()));
        assertEquals(Instant.parse("2026-10-12T00:00:00Z").toEpochMilli(),PublicWarzoneSnapshot.weekStart(Instant.parse("2026-10-12T00:00:00Z").toEpochMilli()));
    }
    @Test void indefiniteOverridesDoNotPublishAnAutomaticExpiry() {
        var runtime=mock(WarzoneRuntime.class);var manager=mock(RotationManager.class);var state=mock(RotationState.class);
        var messages=mock(WarzoneMessageService.class);
        when(runtime.rotations()).thenReturn(manager);when(runtime.messages()).thenReturn(messages);
        when(messages.plain(anyString())).thenAnswer(call->call.getArgument(0));
        when(manager.state()).thenReturn(state);when(manager.scheduleEnabled()).thenReturn(true);
        when(state.overrideActive()).thenReturn(true);when(state.overrideDurationMode()).thenReturn(OverrideDurationMode.UNTIL_CLEARED);
        when(manager.activeSelection()).thenReturn(new ActiveSelection(SelectionSourceType.CUSTOM_OVERRIDE,null,
                new WarzoneConfig.ActiveSet(List.of("elytra-no-rockets"),"Elytra, No Rockets","Rocket boosts blocked",Set.of(),Map.of()),true));
        var output=PublicWarzoneSnapshot.capture(runtime,Instant.parse("2026-10-12T18:00:00Z").toEpochMilli());
        var kit=(Map<?,?>)((List<?>)output.get(ITEMS)).getFirst();
        assertEquals(true,kit.get("manualOverride"));assertFalse(kit.containsKey("endsAt"));
        assertThrows(UnsupportedOperationException.class,()->kit.remove(NAME));
        assertFalse(output.toString().contains("region"));
    }
    @Test void completeWeeklyResultsPreserveExplicitWeekAndStripPrivateFields() {
        String now="2026-10-13T16:00:00Z",week="2026-10-12T00:00:00Z";
        var current=Map.<String,Object>of("generatedAt",now,ITEMS,List.of(Map.of("id","current-meta","type","kit",NAME,"Vanilla")));
        var raw=Map.of("weekStartsAt",week,"generatedAt",now,"complete",true,
                ITEMS,List.of(Map.of("id","00000000-0000-0000-0000-000000000001",NAME,"Player","wins",2,"opponent",PRIVATE_VALUE)));
        var result=PublicWarzoneSnapshot.withLeaders(current,raw);
        assertEquals(week,result.get("weekStartsAt"));
        assertEquals(true,result.get("duelResultsComplete"));
        assertFalse(result.toString().contains(PRIVATE_VALUE));
        var wrongWeek=new java.util.HashMap<String,Object>(raw);wrongWeek.put("weekStartsAt","2026-10-05T00:00:00Z");
        assertTrue(PublicWarzoneSnapshot.withLeaders(current,wrongWeek).toString().contains("unavailable"));
    }
}
