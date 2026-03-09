package com.example.upbit.strategy;

public class Position {
    private final String market;
    private double qty;
    private double avgPrice;
    private int addBuys;

    public Position(String market) {
        this.market = market;
    }

    public String getMarket() {
        return market;
    }

    public double getQty() {
        return qty;
    }

    public void setQty(double qty) {
        this.qty = qty;
    }

    public double getAvgPrice() {
        return avgPrice;
    }

    public void setAvgPrice(double avgPrice) {
        this.avgPrice = avgPrice;
    }

    public int getAddBuys() {
        return addBuys;
    }

    public void setAddBuys(int addBuys) {
        this.addBuys = addBuys;
    }

    public boolean isOpen() {
        return qty > 0.0;
    }

    public void clear() {
        this.qty = 0.0;
        this.avgPrice = 0.0;
        this.addBuys = 0;
    }
}
