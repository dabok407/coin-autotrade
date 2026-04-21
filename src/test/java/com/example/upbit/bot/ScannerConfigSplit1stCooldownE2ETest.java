package com.example.upbit.bot;

import com.example.upbit.db.*;
import com.example.upbit.market.UpbitMarketCatalogService;
import com.example.upbit.upbit.UpbitPrivateClient;
import com.example.upbit.web.AllDayScannerApiController;
import com.example.upbit.web.MorningRushApiController;
import com.example.upbit.web.OpeningScannerApiController;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * V129 Gap #2: UI 필드 E2E — split1stCooldownSec DB 반영 검증.
 *
 * 목적: 설정 화면(settings.html)에서 POST /api/{mr,opening,allday}/config 로
 *       split1stCooldownSec 값을 전송 → Controller 가 repo.save(cfg) 호출
 *       → config 엔티티에 정확히 반영되는지 확인.
 *
 * MockMvc/SpringBootTest 없이 Controller → Repo 저장 경로만 direct invocation
 * 으로 검증 (context 로딩 비용 없이 경로 완전 커버).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
public class ScannerConfigSplit1stCooldownE2ETest {

    // ── MR ──
    @Mock private MorningRushConfigRepository mrRepo;
    @Mock private BotConfigRepository botRepo;
    @Mock private PositionRepository positionRepo;
    @Mock private MorningRushScannerService mrSvc;

    // ── Opening ──
    @Mock private OpeningScannerConfigRepository opRepo;
    @Mock private OpeningScannerService opSvc;
    @Mock private UpbitMarketCatalogService catalogSvc;
    @Mock private UpbitPrivateClient privateClient;

    // ── AllDay ──
    @Mock private AllDayScannerConfigRepository adRepo;
    @Mock private AllDayScannerService adSvc;

    private MorningRushApiController mrCtrl;
    private OpeningScannerApiController opCtrl;
    private AllDayScannerApiController adCtrl;

    @BeforeEach
    public void setUp() throws Exception {
        mrCtrl = new MorningRushApiController();
        injectField(mrCtrl, "configRepo", mrRepo);
        injectField(mrCtrl, "botConfigRepo", botRepo);
        injectField(mrCtrl, "positionRepo", positionRepo);
        injectField(mrCtrl, "scannerService", mrSvc);

        opCtrl = new OpeningScannerApiController(opSvc, opRepo, botRepo, catalogSvc, privateClient, positionRepo);

        adCtrl = new AllDayScannerApiController();
        injectField(adCtrl, "configRepo", adRepo);
        injectField(adCtrl, "botConfigRepo", botRepo);
        injectField(adCtrl, "positionRepo", positionRepo);
        injectField(adCtrl, "scannerService", adSvc);

        // 초기 엔티티: split1stCooldownSec = 기본 60
        MorningRushConfigEntity mrInit = baseMr();
        when(mrRepo.loadOrCreate()).thenReturn(mrInit);

        OpeningScannerConfigEntity opInit = baseOp();
        when(opRepo.loadOrCreate()).thenReturn(opInit);

        AllDayScannerConfigEntity adInit = baseAd();
        when(adRepo.loadOrCreate()).thenReturn(adInit);
    }

    @Test
    @DisplayName("V129-E2E-MR: POST /api/morning-rush/config {split1stCooldownSec:90} → DB에 90 저장")
    public void mr_updateSplit1stCooldown() throws Exception {
        Map<String, Object> body = new HashMap<String, Object>();
        body.put("split1stCooldownSec", 90);

        ResponseEntity<Map<String, Object>> resp = mrCtrl.updateConfig(body);
        assertEquals(200, resp.getStatusCodeValue(), "200 OK");

        ArgumentCaptor<MorningRushConfigEntity> captor =
                ArgumentCaptor.forClass(MorningRushConfigEntity.class);
        verify(mrRepo).save(captor.capture());
        assertEquals(90, captor.getValue().getSplit1stCooldownSec(),
                "MR config 엔티티에 split1stCooldownSec=90 반영");
    }

    @Test
    @DisplayName("V129-E2E-OP: POST /api/opening-scanner/config {split1stCooldownSec:120} → DB에 120 저장")
    public void op_updateSplit1stCooldown() throws Exception {
        Map<String, Object> body = new HashMap<String, Object>();
        body.put("split1stCooldownSec", 120);

        ResponseEntity<Map<String, Object>> resp = opCtrl.updateConfig(body);
        assertEquals(200, resp.getStatusCodeValue(), "200 OK");

        ArgumentCaptor<OpeningScannerConfigEntity> captor =
                ArgumentCaptor.forClass(OpeningScannerConfigEntity.class);
        verify(opRepo).save(captor.capture());
        assertEquals(120, captor.getValue().getSplit1stCooldownSec(),
                "Opening config 엔티티에 split1stCooldownSec=120 반영");
    }

    @Test
    @DisplayName("V129-E2E-AD: POST /api/allday-scanner/config {split1stCooldownSec:45} → DB에 45 저장")
    public void ad_updateSplit1stCooldown() throws Exception {
        Map<String, Object> body = new HashMap<String, Object>();
        body.put("split1stCooldownSec", 45);

        ResponseEntity<Map<String, Object>> resp = adCtrl.updateConfig(body);
        assertEquals(200, resp.getStatusCodeValue(), "200 OK");

        ArgumentCaptor<AllDayScannerConfigEntity> captor =
                ArgumentCaptor.forClass(AllDayScannerConfigEntity.class);
        verify(adRepo).save(captor.capture());
        assertEquals(45, captor.getValue().getSplit1stCooldownSec(),
                "AllDay config 엔티티에 split1stCooldownSec=45 반영");
    }

    @Test
    @DisplayName("V129-E2E-GET: GET /config 응답에 split1stCooldownSec 필드 포함")
    public void get_configExposesField() throws Exception {
        ResponseEntity<Map<String, Object>> mrResp = mrCtrl.getConfig();
        assertTrue(mrResp.getBody().containsKey("split1stCooldownSec"),
                "MR /config 응답에 split1stCooldownSec 포함");
        assertEquals(60, mrResp.getBody().get("split1stCooldownSec"), "기본값 60");

        ResponseEntity<Map<String, Object>> opResp = opCtrl.getConfig();
        assertTrue(opResp.getBody().containsKey("split1stCooldownSec"),
                "Opening /config 응답에 split1stCooldownSec 포함");

        ResponseEntity<Map<String, Object>> adResp = adCtrl.getConfig();
        assertTrue(adResp.getBody().containsKey("split1stCooldownSec"),
                "AllDay /config 응답에 split1stCooldownSec 포함");
    }

    @Test
    @DisplayName("V129-E2E-MISSING: body에 split1stCooldownSec 없으면 기존값 유지")
    public void missingFieldPreservesExisting() throws Exception {
        MorningRushConfigEntity mrInit = baseMr();
        mrInit.setSplit1stCooldownSec(75);  // 기존 75
        when(mrRepo.loadOrCreate()).thenReturn(mrInit);

        Map<String, Object> body = new HashMap<String, Object>();
        body.put("tpPct", 2.5);  // 다른 필드만 수정

        mrCtrl.updateConfig(body);

        ArgumentCaptor<MorningRushConfigEntity> captor =
                ArgumentCaptor.forClass(MorningRushConfigEntity.class);
        verify(mrRepo).save(captor.capture());
        assertEquals(75, captor.getValue().getSplit1stCooldownSec(),
                "필드 누락 시 기존값 75 유지");
    }

    // ═══════════════════════════════════════════════════
    //  Helpers
    // ═══════════════════════════════════════════════════
    private void injectField(Object target, String name, Object value) throws Exception {
        java.lang.reflect.Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    private MorningRushConfigEntity baseMr() {
        MorningRushConfigEntity c = new MorningRushConfigEntity();
        c.setMode("PAPER");
        c.setTpPct(BigDecimal.valueOf(2.3));
        c.setSlPct(BigDecimal.valueOf(3.0));
        c.setTpTrailDropPct(BigDecimal.valueOf(1.0));
        c.setGracePeriodSec(60);
        c.setWidePeriodMin(5);
        c.setWideSlPct(BigDecimal.valueOf(5.0));
        c.setSplitExitEnabled(true);
        c.setSplitTpPct(BigDecimal.valueOf(1.5));
        c.setSplitRatio(BigDecimal.valueOf(0.60));
        c.setTrailDropAfterSplit(BigDecimal.valueOf(2.0));
        c.setSplit1stTrailDrop(BigDecimal.valueOf(2.0));
        c.setSplit1stCooldownSec(60);
        return c;
    }

    private OpeningScannerConfigEntity baseOp() {
        OpeningScannerConfigEntity c = new OpeningScannerConfigEntity();
        c.setMode("PAPER");
        c.setSplit1stCooldownSec(60);
        return c;
    }

    private AllDayScannerConfigEntity baseAd() {
        AllDayScannerConfigEntity c = new AllDayScannerConfigEntity();
        c.setMode("PAPER");
        c.setGracePeriodSec(60);
        c.setSplit1stCooldownSec(60);
        return c;
    }
}
