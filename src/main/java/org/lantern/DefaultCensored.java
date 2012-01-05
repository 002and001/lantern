package org.lantern;

import java.io.IOException;
import java.net.InetAddress;
import java.util.Collection;
import java.util.TreeSet;

import org.apache.commons.lang.StringUtils;
import org.lastbamboo.common.stun.client.PublicIpAddress;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.Sets;

/**
 * Class that keeps track of which countries are considered censored.
 */
public class DefaultCensored implements Censored {

    private final Logger LOG = 
        LoggerFactory.getLogger(DefaultCensored.class);

    private static final Collection<String> CENSORED =
        new TreeSet<String>(Sets.newHashSet(
            // These are taken from ONI data -- 11/16 - any country containing
            // any type of censorship considered "substantial" or "pervasive".
            "AE", "AM", "BH", "CN", "CU", "ET", "ID", "IR", "KP", "KR", 
            "KW", "MM", "OM", "PK", "PS", "QA", "SA", "SD", "SY", "TM", "UZ", 
            "VN", "YE")

        );
    
    public DefaultCensored() {
        CENSORED.add("CU");
        CENSORED.add("KP");
    }

    // These country codes have US export restrictions, and therefore cannot
    // access App Engine sites.
    private final Collection<String> EXPORT_RESTRICTED =
        Sets.newHashSet(
            "SY");

    private String countryCode;
    
    @Override
    public String countryCode() {
        if (StringUtils.isNotBlank(countryCode)) {
            LOG.info("Returning cached country code: {}", countryCode);
            return countryCode;
        }
        
        LOG.info("Returning country code: {}", countryCode);
        countryCode = country().getCode().trim();
        return countryCode;
    }
    
    @Override
    public Country country() {
        final InetAddress address = new PublicIpAddress().getPublicIpAddress();
        return new Country(LanternHub.getGeoIpLookup().getCountry(address));
    }
    
    @Override
    public boolean isCensored() {
        return isCensored(new PublicIpAddress().getPublicIpAddress());
    }
    
    @Override
    public boolean isCensored(final Country country) { 
        final String cc = country.getCode().trim();
        return CENSORED.contains(cc);
    }

    @Override
    public Collection<String> getCensored() {
        return CENSORED;
    }
    
    @Override
    public boolean isCensored(final String address) throws IOException {
        return isCensored(InetAddress.getByName(address));
    }
    
    public boolean isExportRestricted() {
        return isExportRestricted(new PublicIpAddress().getPublicIpAddress());
    }
    
    public boolean isExportRestricted(final InetAddress address) { 
        return isMatch(address, EXPORT_RESTRICTED);
    }

    @Override
    public boolean isExportRestricted(final String address) 
        throws IOException {
        return isExportRestricted(InetAddress.getByName(address));
    }
    
    private boolean isCensored(final InetAddress address) {
        return isMatch(address, CENSORED);
    }
    private boolean isMatch(final InetAddress address, 
        final Collection<String> countries) { 
        return countries.contains(countryCode(address));
    }
    
    private String countryCode(final InetAddress address) {
        final com.maxmind.geoip.Country country = 
            LanternHub.getGeoIpLookup().getCountry(address);
        LOG.info("Country is: {}", country.getName());
        return country.getCode().trim();
    }
}
