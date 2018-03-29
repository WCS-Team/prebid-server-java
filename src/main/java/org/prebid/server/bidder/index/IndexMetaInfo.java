package org.prebid.server.bidder.index;

import org.prebid.server.bidder.MetaInfo;
import org.prebid.server.proto.response.BidderInfo;

import java.util.Arrays;
import java.util.Collections;

public class IndexMetaInfo implements MetaInfo {

    @Override
    public BidderInfo info() {
        return BidderInfo.create("info@prebid.org",
                Collections.singletonList("banner"),
                Arrays.asList("banner", "video"), null);
    }
}