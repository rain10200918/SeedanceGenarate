package org.example.seedancegenarate.agent.skill;

import java.util.List;

/** Provider-neutral public text search; never fetches destination pages or executes their instructions. */
public interface SearchProvider {
    boolean available();
    Result search(Request request);
    record Request(String query, List<String> domains, String timeRange) {}
    record Source(String title, String url, String snippet) {}
    record Result(String provider, List<Source> sources) {}
    static String clip(String value,int limit) {return value.length()>limit?value.substring(0,limit):value;}
    /** Links are not fetched: reject credentials, numeric hosts and local-only suffixes. */
    static boolean safePublicUrl(String value) {
        try {
            if(value==null||value.length()>2048)return false;
            var uri=java.net.URI.create(value);String host=uri.getHost();
            if(!java.util.Set.of("https","http").contains(uri.getScheme())||uri.getUserInfo()!=null||host==null)return false;
            host=host.toLowerCase(java.util.Locale.ROOT);
            if(!host.matches("[a-z0-9-]+(?:\\.[a-z0-9-]+)+")||!host.matches(".*\\.[a-z]{2,63}"))return false;
            for(String suffix:java.util.List.of(".localhost",".local",".internal",".test",".lan",".home",".invalid"))
                if(host.endsWith(suffix))return false;
            return true;
        } catch(IllegalArgumentException e) {return false;}
    }
    /** Deliberately contains only a safe code, never provider response bodies or credentials. */
    final class Failure extends RuntimeException {
        private final String code;
        private final boolean retryable;
        public Failure(String code, boolean retryable) { super(code);this.code=code;this.retryable=retryable; }
        public String code() { return code; }
        public boolean retryable() { return retryable; }
    }
}
