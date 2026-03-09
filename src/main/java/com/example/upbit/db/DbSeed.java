package com.example.upbit.db;

import com.example.upbit.config.BotProperties;
import com.example.upbit.config.TradeProperties;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.List;
import java.math.BigDecimal;

@Component
public class DbSeed implements ApplicationRunner {

    private final BotConfigRepository botRepo;
    private final MarketConfigRepository marketRepo;
    private final BotProperties botProps;
    private final TradeProperties tradeProps;

    public DbSeed(BotConfigRepository botRepo, MarketConfigRepository marketRepo, BotProperties botProps, TradeProperties tradeProps) {
        this.botRepo = botRepo;
        this.marketRepo = marketRepo;
        this.botProps = botProps;
        this.tradeProps = tradeProps;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (botRepo.count() == 0) {
            BotConfigEntity c = new BotConfigEntity();
            c.setMode("PAPER");
            c.setCandleUnitMin(botProps.getDefaultCandleUnitMinutes());
            c.setCapitalKrw(BigDecimal.valueOf(0));
            // default order sizing = FIXED (globalBaseOrderKrw)
            c.setOrderSizingMode("FIXED");
            c.setOrderSizingValue(BigDecimal.valueOf(tradeProps.getGlobalBaseOrderKrw()));
            c.setMaxAddBuysGlobal(2);
            botRepo.save(c);
        }

        if (marketRepo.count() == 0) {
            List<String> defaults = botProps.getDefaultMarkets();
            for (String m : defaults) {
                MarketConfigEntity mc = new MarketConfigEntity();
                mc.setMarket(m);
                mc.setEnabled(true);
                mc.setBaseOrderKrw(BigDecimal.valueOf(tradeProps.getGlobalBaseOrderKrw()));
                marketRepo.save(mc);
            }
        }
    }
}
