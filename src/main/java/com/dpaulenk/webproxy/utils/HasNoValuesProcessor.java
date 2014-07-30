package com.dpaulenk.webproxy.utils;

public class HasNoValuesProcessor implements Processor<String> {
    private final String[] values;

    public HasNoValuesProcessor(String... values) {
        this.values = values;
    }

    public boolean process(String value) {
        for (String val : values) {
            if (val.equals(value)) {
                return false;
            }
        }
        return true;
    }
}
