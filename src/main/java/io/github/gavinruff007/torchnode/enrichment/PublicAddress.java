package io.github.gavinruff007.torchnode.enrichment;

import io.github.gavinruff007.torchnode.model.EndpointAddress;

/** Conservative eligibility policy; does not assert reachability or geolocation. */
public final class PublicAddress {
    private static final String[] V4={"0.0.0.0/8","10.0.0.0/8","100.64.0.0/10","127.0.0.0/8","169.254.0.0/16","172.16.0.0/12","192.0.0.0/24","192.0.2.0/24","192.168.0.0/16","198.18.0.0/15","198.51.100.0/24","203.0.113.0/24","224.0.0.0/4","240.0.0.0/4"};
    private static final String[] V6={"2001::/32","2001:2::/48","2001:10::/28","2001:20::/28","2001:db8::/32","2002::/16"};
    public static String exclusion(String literal) {
        byte[] bytes=EndpointAddress.parse(literal).getAddress();
        if(bytes.length==4) {
            for(String prefix:V4)if(in(bytes,prefix))return "Non-public or special-purpose IPv4 range "+prefix;
        } else {
            if(!in(bytes,"2000::/3"))return "Not native global-unicast IPv6 (includes local, mapped, unspecified and multicast addresses)";
            for(String prefix:V6)if(in(bytes,prefix))return "Special-purpose IPv6 range "+prefix;
        }
        return null;
    }
    private static boolean in(byte[] address,String cidr) {
        int split=cidr.indexOf('/'),bits=Integer.parseInt(cidr.substring(split+1));
        byte[] network=EndpointAddress.parse(cidr.substring(0,split)).getAddress();
        if(address.length!=network.length)return false;
        for(int i=0;i<bits;i++)if(((address[i/8]^network[i/8])&(1<<(7-i%8)))!=0)return false;
        return true;
    }
}
