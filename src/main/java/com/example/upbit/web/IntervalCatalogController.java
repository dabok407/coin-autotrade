package com.example.upbit.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * FE에서 Interval(분봉/일봉) 옵션을 동적으로 가져가기 위한 엔드포인트.
 *
 * Upbit 기본 제공:
 * - 분봉: 1,3,5,10,15,30,60,240
 * - 일봉: 1일
 */
@RestController
public class IntervalCatalogController {

    @GetMapping("/api/intervals")
    public List<IntervalInfo> intervals() {
        List<IntervalInfo> out = new ArrayList<IntervalInfo>();
        for (CandleInterval ci : CandleInterval.values()) {
            out.add(new IntervalInfo(ci.key, ci.label, ci.unitMin));
        }
        return out;
    }

    /**
     * UI value는 key(예: 5m, 240m, 1d)로 통일.
     * 내부 저장/실행은 unitMin(분)으로 사용.
     */
    public enum CandleInterval {
        M1("1m", "1분", 1),
        M3("3m", "3분", 3),
        M5("5m", "5분", 5),
        M10("10m", "10분", 10),
        M15("15m", "15분", 15),
        M30("30m", "30분", 30),
        H1("60m", "1시간(60분)", 60),
        H4("240m", "4시간(240분)", 240),
        D1("1d", "1일", 1440);

        public final String key;
        public final String label;
        public final int unitMin;

        CandleInterval(String key, String label, int unitMin) {
            this.key = key;
            this.label = label;
            this.unitMin = unitMin;
        }

        public static CandleInterval fromKey(String key) {
            if (key == null) return null;
            for (CandleInterval ci : values()) {
                if (ci.key.equalsIgnoreCase(key.trim())) return ci;
            }
            return null;
        }

        public static CandleInterval fromUnitMin(int unitMin) {
            for (CandleInterval ci : values()) {
                if (ci.unitMin == unitMin) return ci;
            }
            return null;
        }
    }

    public static class IntervalInfo {
        public String key;
        public String label;
        public int unitMin;

        public IntervalInfo(String key, String label, int unitMin) {
            this.key = key;
            this.label = label;
            this.unitMin = unitMin;
        }
    }
}
