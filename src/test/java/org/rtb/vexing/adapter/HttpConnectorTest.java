package org.rtb.vexing.adapter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.iab.openrtb.request.App;
import com.iab.openrtb.request.BidRequest;
import com.iab.openrtb.request.BidRequest.BidRequestBuilder;
import com.iab.openrtb.request.Format;
import com.iab.openrtb.request.User;
import com.iab.openrtb.response.Bid;
import com.iab.openrtb.response.Bid.BidBuilder;
import com.iab.openrtb.response.BidResponse;
import com.iab.openrtb.response.BidResponse.BidResponseBuilder;
import com.iab.openrtb.response.SeatBid;
import com.iab.openrtb.response.SeatBid.SeatBidBuilder;
import io.netty.channel.ConnectTimeoutException;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.MultiMap;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpClientResponse;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.BDDMockito;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;
import org.mockito.stubbing.Answer;
import org.rtb.vexing.VertxTest;
import org.rtb.vexing.adapter.model.ExchangeCall;
import org.rtb.vexing.adapter.model.HttpRequest;
import org.rtb.vexing.cookie.UidsCookie;
import org.rtb.vexing.exception.PreBidException;
import org.rtb.vexing.execution.GlobalTimeout;
import org.rtb.vexing.model.AdUnitBid;
import org.rtb.vexing.model.AdUnitBid.AdUnitBidBuilder;
import org.rtb.vexing.model.Bidder;
import org.rtb.vexing.model.BidderResult;
import org.rtb.vexing.model.MediaType;
import org.rtb.vexing.model.PreBidRequestContext;
import org.rtb.vexing.model.PreBidRequestContext.PreBidRequestContextBuilder;
import org.rtb.vexing.model.request.PreBidRequest;
import org.rtb.vexing.model.request.PreBidRequest.PreBidRequestBuilder;
import org.rtb.vexing.model.response.BidderDebug;
import org.rtb.vexing.model.response.UsersyncInfo;

import java.io.IOException;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.stream.Collectors;

import static java.util.Arrays.asList;
import static java.util.Collections.*;
import static java.util.function.Function.identity;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.data.Offset.offset;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

public class HttpConnectorTest extends VertxTest {

    @Rule
    public final MockitoRule mockitoRule = MockitoJUnit.rule();

    @Mock
    private Adapter adapter;
    @Mock
    private HttpClient httpClient;
    @Mock
    private HttpClientRequest httpClientRequest;
    @Mock
    private UidsCookie uidsCookie;

    private Bidder bidder;
    private PreBidRequestContext preBidRequestContext;
    private HttpConnector httpConnector;

    @Before
    public void setUp() {
        given(adapter.makeHttpRequests(any(Bidder.class), any(PreBidRequestContext.class)))
                .willReturn(singletonList(givenHttpRequest(null, identity())));

        given(httpClient.postAbs(anyString(), any())).willReturn(httpClientRequest);

        given(httpClientRequest.headers()).willReturn(MultiMap.caseInsensitiveMultiMap());
        given(httpClientRequest.setTimeout(anyLong())).willReturn(httpClientRequest);
        given(httpClientRequest.exceptionHandler(any())).willReturn(httpClientRequest);

        bidder = Bidder.of(null, null);
        preBidRequestContext = givenPreBidRequestContext(identity(), identity());
        httpConnector = new HttpConnector(httpClient);
    }

    @Test
    public void creationShouldFailOnNullArguments() {
        assertThatNullPointerException().isThrownBy(() -> new HttpConnector(null));
    }

    @Test
    public void callShouldFailOnNullArguments() {
        assertThatNullPointerException().isThrownBy(() -> httpConnector.call(null, null, null));
        assertThatNullPointerException().isThrownBy(() -> httpConnector.call(adapter, null, null));
        assertThatNullPointerException().isThrownBy(() -> httpConnector.call(adapter, bidder, null));
    }

    @Test
    public void callShouldPerformHttpRequestsWithExpectedHeaders() {
        // given
        final MultiMap headers = MultiMap.caseInsensitiveMultiMap()
                .add("key1", "value1");
        given(adapter.makeHttpRequests(any(Bidder.class), any(PreBidRequestContext.class)))
                .willReturn(singletonList(givenHttpRequest(headers, identity())));

        // when
        httpConnector.call(adapter, bidder, preBidRequestContext);

        // then
        assertThat(httpClientRequest.headers()).extracting(Map.Entry::getKey).containsOnly("key1");
        assertThat(httpClientRequest.headers()).extracting(Map.Entry::getValue).containsOnly("value1");
    }

    @Test
    public void callShouldPerformHttpRequestsWithExpectedTimeout() {
        // when
        httpConnector.call(adapter, bidder, preBidRequestContext);

        // then
        final ArgumentCaptor<Long> timeoutCaptor = ArgumentCaptor.forClass(Long.class);
        verify(httpClientRequest).setTimeout(timeoutCaptor.capture());
        assertThat(timeoutCaptor.getValue()).isCloseTo(1000L, offset(20L));
    }

    @Test
    public void callShouldPerformHttpRequestsWithExpectedBody() throws IOException {
        // given
        given(adapter.makeHttpRequests(any(Bidder.class), any(PreBidRequestContext.class)))
                .willReturn(singletonList(givenHttpRequest(null, b -> b.id("bidRequest1"))));

        // when
        httpConnector.call(adapter, bidder, preBidRequestContext);

        // then
        final BidRequest bidRequest = captureBidRequest();
        assertThat(bidRequest).isNotNull();
        assertThat(bidRequest.getId()).isEqualTo("bidRequest1");
    }

    @Test
    public void callShouldNotPerformHttpRequestsIfAdapterReturnsEmptyHttpRequests() {
        // given
        given(adapter.makeHttpRequests(any(Bidder.class), any(PreBidRequestContext.class)))
                .willReturn(emptyList());

        // when
        httpConnector.call(adapter, bidder, preBidRequestContext);

        // then
        verifyZeroInteractions(httpClient);
    }

    @Test
    public void callShouldSubmitErrorToAdapterIfMakeHttpRequestsFails() {
        // given
        given(adapter.makeHttpRequests(any(Bidder.class), any(PreBidRequestContext.class)))
                .willThrow(new PreBidException("Make http requests exception"));

        // when
        final Future<BidderResult> bidderResultFuture = httpConnector.call(adapter, bidder, preBidRequestContext);

        // then
        final BidderResult bidderResult = bidderResultFuture.result();
        assertThat(bidderResult.getBidderStatus().getError()).isEqualTo("Make http requests exception");
    }

    @Test
    public void callShouldSubmitTimeOutErrorToAdapterIfGlobalTimeoutAlreadyExpired() {
        // given
        preBidRequestContext = givenPreBidRequestContext(
                builder -> builder.timeout(GlobalTimeout.create(Clock.systemDefaultZone().millis() - 10000, 1000)),
                identity());

        // when
        final Future<BidderResult> bidderResultFuture = httpConnector.call(adapter, bidder, preBidRequestContext);

        // then
        final BidderResult bidderResult = bidderResultFuture.result();
        assertThat(bidderResult.isTimedOut()).isTrue();
        assertThat(bidderResult.getBidderStatus()).isNotNull();
        assertThat(bidderResult.getBidderStatus().getError()).isEqualTo("Timed out");
        verifyZeroInteractions(httpClient);
    }

    @Test
    public void callShouldSubmitTimeOutErrorToAdapterIfConnectTimeoutOccurs() {
        // given
        given(httpClientRequest.exceptionHandler(any()))
                .willAnswer(withSelfAndPassObjectToHandler(new ConnectTimeoutException()));

        // when
        final Future<BidderResult> bidderResultFuture = httpConnector.call(adapter, bidder, preBidRequestContext);

        // then
        final BidderResult bidderResult = bidderResultFuture.result();
        assertThat(bidderResult.isTimedOut()).isTrue();
        assertThat(bidderResult.getBidderStatus()).isNotNull();
        assertThat(bidderResult.getBidderStatus().getError()).isEqualTo("Timed out");
    }

    @Test
    public void callShouldSubmitTimeOutErrorToAdapterIfTimeoutOccurs() {
        // given
        given(httpClientRequest.exceptionHandler(any()))
                .willAnswer(withSelfAndPassObjectToHandler(new TimeoutException()));

        // when
        final Future<BidderResult> bidderResultFuture = httpConnector.call(adapter, bidder, preBidRequestContext);

        // then
        final BidderResult bidderResult = bidderResultFuture.result();
        assertThat(bidderResult.isTimedOut()).isTrue();
        assertThat(bidderResult.getBidderStatus()).isNotNull();
        assertThat(bidderResult.getBidderStatus().getError()).isEqualTo("Timed out");
    }

    @Test
    public void callShouldSubmitErrorToAdapterIfHttpRequestFails() {
        // given
        given(httpClientRequest.exceptionHandler(any()))
                .willAnswer(withSelfAndPassObjectToHandler(new RuntimeException("Request exception")));

        // when
        final Future<BidderResult> bidderResultFuture = httpConnector.call(adapter, bidder, preBidRequestContext);

        // then
        final BidderResult bidderResult = bidderResultFuture.result();
        assertThat(bidderResult.getBidderStatus().getError()).isEqualTo("Request exception");
    }

    @Test
    public void callShouldSubmitErrorToAdapterIfReadingHttpResponseFails() {
        // given
        givenHttpClientProducesException(new RuntimeException("Response exception"));

        // when
        final Future<BidderResult> bidderResultFuture = httpConnector.call(adapter, bidder, preBidRequestContext);

        // then
        final BidderResult bidderResult = bidderResultFuture.result();
        assertThat(bidderResult.getBidderStatus().getError()).isEqualTo("Response exception");
    }

    @Test
    public void callShouldSubmitErrorToAdapterIfHttpResponseStatusCodeIs204() {
        // given
        givenHttpClientReturnsResponses(204, "response");

        // when
        final Future<BidderResult> bidderResultFuture = httpConnector.call(adapter, bidder, preBidRequestContext);

        // then
        final BidderResult bidderResult = bidderResultFuture.result();
        assertThat(bidderResult.getBidderStatus().getError()).isNull();
        assertThat(bidderResult.getBids()).isEmpty();
    }

    @Test
    public void callShouldSubmitErrorToAdapterIfHttpResponseStatusCodeIsNot200Or204() {
        // given
        givenHttpClientReturnsResponses(503, "response");

        // when
        final Future<BidderResult> bidderResultFuture = httpConnector.call(adapter, bidder, preBidRequestContext);

        // then
        final BidderResult bidderResult = bidderResultFuture.result();
        assertThat(bidderResult.getBidderStatus().getError()).isEqualTo("HTTP status 503; body: response");
    }

    @Test
    public void callShouldSubmitErrorToAdapterIfHttpResponseBodyCouldNotBeParsed() {
        // given
        givenHttpClientReturnsResponses(200, "response");

        // when
        final Future<BidderResult> bidderResultFuture = httpConnector.call(adapter, bidder, preBidRequestContext);

        // then
        final BidderResult bidderResult = bidderResultFuture.result();
        assertThat(bidderResult.getBidderStatus().getError()).startsWith("Failed to decode");
    }

    @Test
    public void callShouldReturnBidderResultWithoutErrorIfBidsArePresent() throws JsonProcessingException {
        // given
        final AdUnitBid adUnitBid = givenAdUnitBid(identity());
        bidder = Bidder.of("bidderCode1", asList(adUnitBid, adUnitBid));

        given(adapter.extractBids(any(Bidder.class), any(ExchangeCall.class)))
                .willReturn(singletonList(org.rtb.vexing.model.response.Bid.builder()));

        final String bidResponse = givenBidResponse(identity(), identity(), singletonList(identity()));
        final HttpClientResponse httpClientResponse = givenHttpClientResponse(200);
        given(httpClientResponse.bodyHandler(any()))
                .willAnswer(withSelfAndPassObjectToHandler(Buffer.buffer(bidResponse)))
                .willReturn(httpClientResponse);

        // when
        final Future<BidderResult> bidderResultFuture = httpConnector.call(adapter, bidder, preBidRequestContext);

        // then
        final BidderResult bidderResult = bidderResultFuture.result();
        assertThat(bidderResult.getBidderStatus().getError()).isNull();
        assertThat(bidderResult.getBids()).hasSize(1);
    }

    @Test
    public void
    callShouldReturnBidderResultWithErrorIfAtLeastOneErrorOccursWhileHttpRequestForNotToleratedErrorsAdapter()
            throws JsonProcessingException {
        // given
        given(adapter.makeHttpRequests(any(Bidder.class), any(PreBidRequestContext.class)))
                .willReturn(asList(givenHttpRequest(null, identity()),
                        givenHttpRequest(null, identity())));

        final AdUnitBid adUnitBid = givenAdUnitBid(identity());
        bidder = Bidder.of("bidderCode1", asList(adUnitBid, adUnitBid));

        given(adapter.extractBids(any(Bidder.class), any(ExchangeCall.class)))
                .willReturn(singletonList(org.rtb.vexing.model.response.Bid.builder()))
                .willReturn(singletonList(org.rtb.vexing.model.response.Bid.builder()));

        final String bidResponse = givenBidResponse(identity(), identity(), singletonList(identity()));
        final HttpClientResponse httpClientResponse = mock(HttpClientResponse.class);
        given(httpClientResponse.statusCode()).willReturn(200);
        given(httpClientResponse.bodyHandler(any()))
                .willAnswer(withSelfAndPassObjectToHandler(Buffer.buffer(bidResponse)))
                .willReturn(httpClientResponse);

        final HttpClientResponse httpClientResponseWithError = mock(HttpClientResponse.class);
        given(httpClientResponseWithError.statusCode()).willReturn(503);
        given(httpClientResponseWithError.bodyHandler(any()))
                .willAnswer(withSelfAndPassObjectToHandler(Buffer.buffer("error response")))
                .willReturn(httpClientResponseWithError);

        given(httpClient.postAbs(anyString(), any()))
                .willAnswer(withRequestAndPassResponseToHandler(httpClientResponse))
                .willAnswer(withRequestAndPassResponseToHandler(httpClientResponseWithError));

        // when
        final Future<BidderResult> bidderResultFuture = httpConnector.call(adapter, bidder, preBidRequestContext);

        // then
        final BidderResult bidderResult = bidderResultFuture.result();
        assertThat(bidderResult.getBidderStatus().getError()).isNotNull()
                .startsWith("HTTP status 503; body:");
    }

    @Test
    public void
    callShouldReturnBidderResultWithoutErrorIfAtLeastOneBidIsPresentWhileHttpRequestForToleratedErrorsAdapter()
            throws JsonProcessingException {
        // given
        given(adapter.makeHttpRequests(any(Bidder.class), any(PreBidRequestContext.class)))
                .willReturn(asList(givenHttpRequest(null, identity()),
                        givenHttpRequest(null, identity())));

        given(adapter.tolerateErrors()).willReturn(true);

        final AdUnitBid adUnitBid = givenAdUnitBid(identity());
        bidder = Bidder.of("bidderCode1", asList(adUnitBid, adUnitBid));

        given(adapter.extractBids(any(Bidder.class), any(ExchangeCall.class)))
                .willReturn(singletonList(org.rtb.vexing.model.response.Bid.builder()))
                .willReturn(null);

        final String bidResponse = givenBidResponse(identity(), identity(), singletonList(identity()));
        final HttpClientResponse httpClientResponse = mock(HttpClientResponse.class);
        given(httpClientResponse.statusCode()).willReturn(200);
        given(httpClientResponse.bodyHandler(any()))
                .willAnswer(withSelfAndPassObjectToHandler(Buffer.buffer(bidResponse)))
                .willReturn(httpClientResponse);

        final HttpClientResponse httpClientResponseWithError = mock(HttpClientResponse.class);
        given(httpClientResponseWithError.statusCode()).willReturn(503);
        given(httpClientResponseWithError.bodyHandler(any()))
                .willAnswer(withSelfAndPassObjectToHandler(Buffer.buffer("error response")))
                .willReturn(httpClientResponseWithError);

        given(httpClient.postAbs(anyString(), any()))
                .willAnswer(withRequestAndPassResponseToHandler(httpClientResponse))
                .willAnswer(withRequestAndPassResponseToHandler(httpClientResponseWithError));

        // when
        final Future<BidderResult> bidderResultFuture = httpConnector.call(adapter, bidder, preBidRequestContext);

        // then
        final BidderResult bidderResult = bidderResultFuture.result();
        assertThat(bidderResult.getBidderStatus().getError()).isNull();
        assertThat(bidderResult.getBids()).hasSize(1);
    }

    @Test
    public void
    callShouldReturnBidderResultWithErrorIfAtLeastOneErrorOccursWhileExtractingForNotToleratedErrorsAdapter()
            throws JsonProcessingException {
        // given
        given(adapter.makeHttpRequests(any(Bidder.class), any(PreBidRequestContext.class)))
                .willReturn(asList(givenHttpRequest(null, identity()),
                        givenHttpRequest(null, identity())));

        final AdUnitBid adUnitBid = givenAdUnitBid(identity());
        bidder = Bidder.of("bidderCode1", asList(adUnitBid, adUnitBid));

        given(adapter.extractBids(any(Bidder.class), any(ExchangeCall.class)))
                .willReturn(singletonList(org.rtb.vexing.model.response.Bid.builder()))
                .willThrow(new PreBidException("adapter extractBids exception"));

        final String bidResponse = givenBidResponse(identity(), identity(), singletonList(identity()));
        givenHttpClientReturnsResponses(200, bidResponse, bidResponse);

        // when
        final Future<BidderResult> bidderResultFuture = httpConnector.call(adapter, bidder, preBidRequestContext);

        // then
        final BidderResult bidderResult = bidderResultFuture.result();
        assertThat(bidderResult.getBidderStatus().getError()).isNotNull()
                .isEqualTo("adapter extractBids exception");
    }

    @Test
    public void
    callShouldReturnBidderResultWithoutErrorIfAtLeastOneBidIsPresentWhileExtractingForToleratedErrorsAdapter()
            throws JsonProcessingException {
        // given
        given(adapter.makeHttpRequests(any(Bidder.class), any(PreBidRequestContext.class)))
                .willReturn(asList(givenHttpRequest(null, identity()),
                        givenHttpRequest(null, identity())));

        given(adapter.tolerateErrors()).willReturn(true);

        final AdUnitBid adUnitBid = givenAdUnitBid(identity());
        bidder = Bidder.of("bidderCode1", asList(adUnitBid, adUnitBid));

        given(adapter.extractBids(any(Bidder.class), any(ExchangeCall.class)))
                .willReturn(singletonList(org.rtb.vexing.model.response.Bid.builder()))
                .willThrow(new PreBidException("adapter extractBids exception"));

        final String bidResponse = givenBidResponse(identity(), identity(), singletonList(identity()));
        givenHttpClientReturnsResponses(200, bidResponse, bidResponse);

        // when
        final Future<BidderResult> bidderResultFuture = httpConnector.call(adapter, bidder, preBidRequestContext);

        // then
        final BidderResult bidderResult = bidderResultFuture.result();
        assertThat(bidderResult.getBidderStatus().getError()).isNull();
        assertThat(bidderResult.getBids()).hasSize(1);
    }

    @Test
    public void callShouldReturnBidderResultWithEmptyBidsIfAdUnitBidIsBannerAndSizesLengthMoreThanOne()
            throws JsonProcessingException {
        // given
        bidder = Bidder.of("bidderCode1", singletonList(
                givenAdUnitBid(builder -> builder
                        .adUnitCode("adUnitCode1")
                        .sizes(asList(Format.builder().w(100).h(200).build(), Format.builder().w(100).h(200).build()))
                        .bidId("bidId1"))));

        given(adapter.extractBids(any(Bidder.class), any(ExchangeCall.class)))
                .willReturn(singletonList(org.rtb.vexing.model.response.Bid.builder()
                        .code("adUnitCode1")
                        .bidId("bidId1")
                        .mediaType(MediaType.banner)));

        givenHttpClientReturnsResponses(200,
                givenBidResponse(identity(), identity(), singletonList(identity())));

        // when
        final Future<BidderResult> bidderResultFuture = httpConnector.call(adapter, bidder, preBidRequestContext);

        // then
        final BidderResult bidderResult = bidderResultFuture.result();
        assertThat(bidderResult.getBids()).isEmpty();
        assertThat(bidderResult.getBidderStatus().getNumBids()).isEqualTo(0);
    }

    @Test
    public void callShouldReturnBidderResultWithNoCookieIfNoAdapterUidInCookieAndNoAppInPreBidRequest()
            throws IOException {
        // given
        givenHttpClientReturnsResponses(200,
                givenBidResponse(identity(), identity(), singletonList(identity())));

        given(adapter.usersyncInfo()).willReturn(UsersyncInfo.of("url1", null, false));

        // when
        final Future<BidderResult> bidderResultFuture = httpConnector.call(adapter, bidder, preBidRequestContext);

        // then
        final BidderResult bidderResult = bidderResultFuture.result();
        assertThat(bidderResult.getBidderStatus().getNoCookie()).isTrue();
        assertThat(bidderResult.getBidderStatus().getUsersync()).isNotNull();
        assertThat(bidderResult.getBidderStatus().getUsersync()).isEqualTo(UsersyncInfo.of("url1", null, false));
    }

    @Test
    public void callShouldReturnBidderResultWithoutNoCookieIfNoAdapterUidInCookieAndAppPresentInPreBidRequest()
            throws IOException {
        // given
        preBidRequestContext = givenPreBidRequestContext(identity(),
                builder -> builder.app(App.builder().build()).user(User.builder().build()));

        givenHttpClientReturnsResponses(200,
                givenBidResponse(identity(), identity(), singletonList(identity())));

        // when
        final Future<BidderResult> bidderResultFuture = httpConnector.call(adapter, bidder, preBidRequestContext);

        // then
        final BidderResult bidderResult = bidderResultFuture.result();
        assertThat(bidderResult.getBidderStatus().getNoCookie()).isNull();
        assertThat(bidderResult.getBidderStatus().getUsersync()).isNull();
    }

    @Test
    public void callShouldReturnBidderResultWithDebugIfFlagIsTrue() throws JsonProcessingException {
        // given
        preBidRequestContext = givenPreBidRequestContext(builder -> builder.isDebug(true), identity());

        bidder = Bidder.of("bidderCode1", asList(
                givenAdUnitBid(builder -> builder.adUnitCode("adUnitCode1")),
                givenAdUnitBid(builder -> builder.adUnitCode("adUnitCode2"))));

        final String bidResponse = givenBidResponse(builder -> builder.id("bidResponseId1"),
                identity(),
                asList(bidBuilder -> bidBuilder.impid("adUnitCode1"), bidBuilder -> bidBuilder.impid("adUnitCode2")));
        givenHttpClientReturnsResponses(200, bidResponse);

        // when
        final Future<BidderResult> bidderResultFuture = httpConnector.call(adapter, bidder, preBidRequestContext);

        // then
        final BidderResult bidderResult = bidderResultFuture.result();

        final ArgumentCaptor<String> bidRequestCaptor = ArgumentCaptor.forClass(String.class);
        verify(httpClientRequest).end(bidRequestCaptor.capture());
        final List<String> bidRequests = bidRequestCaptor.getAllValues();

        assertThat(bidderResult.getBidderStatus().getDebug()).hasSize(1).containsOnly(
                BidderDebug.builder()
                        .requestUri("uri")
                        .requestBody(bidRequests.get(0))
                        .responseBody(bidResponse)
                        .statusCode(200)
                        .build());
    }

    @Test
    public void callShouldReturnBidderResultWithoutDebugIfFlagIsFalse() throws JsonProcessingException {
        // given
        preBidRequestContext = givenPreBidRequestContext(builder -> builder.isDebug(false), identity());

        givenHttpClientReturnsResponses(200,
                givenBidResponse(identity(), identity(), singletonList(identity())));

        // when
        final Future<BidderResult> bidderResultFuture = httpConnector.call(adapter, bidder, preBidRequestContext);

        // then
        assertThat(bidderResultFuture.result().getBidderStatus().getDebug()).isNull();
    }

    @Test
    public void callShouldReturnBidderResultWithDebugIfFlagIsTrueAndGlobalTimeoutAlreadyExpired() {
        // given
        preBidRequestContext = givenPreBidRequestContext(
                builder -> builder
                        .timeout(GlobalTimeout.create(Clock.systemDefaultZone().millis() - 10000, 1000))
                        .isDebug(true),
                identity());

        // when
        final Future<BidderResult> bidderResultFuture = httpConnector.call(adapter, bidder, preBidRequestContext);

        // then
        final BidderResult bidderResult = bidderResultFuture.result();
        assertThat(bidderResult.getBidderStatus().getDebug()).hasSize(1);

        final BidderDebug bidderDebug = bidderResult.getBidderStatus().getDebug().get(0);
        assertThat(bidderDebug.getRequestUri()).isNotBlank();
        assertThat(bidderDebug.getRequestBody()).isNotBlank();
    }

    @Test
    public void callShouldReturnBidderResultWithDebugIfFlagIsTrueAndHttpRequestFails() {
        // given
        preBidRequestContext = givenPreBidRequestContext(builder -> builder.isDebug(true), identity());

        given(httpClientRequest.exceptionHandler(any()))
                .willAnswer(withSelfAndPassObjectToHandler(new RuntimeException("Request exception")));

        // when
        final Future<BidderResult> bidderResultFuture = httpConnector.call(adapter, bidder, preBidRequestContext);

        // then
        final BidderResult bidderResult = bidderResultFuture.result();
        assertThat(bidderResult.getBidderStatus().getDebug()).hasSize(1);

        final BidderDebug bidderDebug = bidderResult.getBidderStatus().getDebug().get(0);
        assertThat(bidderDebug.getRequestUri()).isNotBlank();
        assertThat(bidderDebug.getRequestBody()).isNotBlank();
    }

    @Test
    public void callShouldReturnBidderResultWithDebugIfFlagIsTrueAndResponseIsNotSuccessful() {
        // given
        preBidRequestContext = givenPreBidRequestContext(builder -> builder.isDebug(true), identity());

        givenHttpClientReturnsResponses(503, "response");

        // when
        final Future<BidderResult> bidderResultFuture = httpConnector.call(adapter, bidder, preBidRequestContext);

        // then
        final BidderResult bidderResult = bidderResultFuture.result();
        assertThat(bidderResult.getBidderStatus().getDebug()).hasSize(1);

        final BidderDebug bidderDebug = bidderResult.getBidderStatus().getDebug().get(0);
        assertThat(bidderDebug.getRequestUri()).isNotBlank();
        assertThat(bidderDebug.getRequestBody()).isNotBlank();
        assertThat(bidderDebug.getResponseBody()).isNotBlank();
        assertThat(bidderDebug.getStatusCode()).isPositive();
    }

    private BidRequest captureBidRequest() throws IOException {
        final ArgumentCaptor<String> bidRequestCaptor = ArgumentCaptor.forClass(String.class);
        verify(httpClientRequest).end(bidRequestCaptor.capture());
        return mapper.readValue(bidRequestCaptor.getValue(), BidRequest.class);
    }

    private static HttpRequest givenHttpRequest(
            MultiMap headers, Function<BidRequestBuilder, BidRequestBuilder> bidRequestBuilderCustomizer) {

        return HttpRequest.of("uri", headers, bidRequestBuilderCustomizer.apply(BidRequest.builder()).build());
    }

    private static AdUnitBid givenAdUnitBid(Function<AdUnitBidBuilder, AdUnitBidBuilder> adUnitBidBuilderCustomizer) {
        final AdUnitBidBuilder adUnitBidBuilderMinimal = AdUnitBid.builder()
                .adUnitCode("adUnitCode1")
                .sizes(singletonList(Format.builder().w(300).h(250).build()))
                .mediaTypes(singleton(MediaType.banner));
        return adUnitBidBuilderCustomizer.apply(adUnitBidBuilderMinimal).build();
    }

    private PreBidRequestContext givenPreBidRequestContext(
            Function<PreBidRequestContextBuilder, PreBidRequestContextBuilder> preBidRequestContextBuilderCustomizer,
            Function<PreBidRequestBuilder, PreBidRequestBuilder> preBidRequestBuilderCustomizer) {

        final PreBidRequestBuilder preBidRequestBuilderMinimal = PreBidRequest.builder()
                .accountId("accountId");
        final PreBidRequest preBidRequest = preBidRequestBuilderCustomizer.apply(preBidRequestBuilderMinimal).build();

        final PreBidRequestContextBuilder preBidRequestContextBuilderMinimal =
                PreBidRequestContext.builder()
                        .preBidRequest(preBidRequest)
                        .uidsCookie(uidsCookie)
                        .timeout(GlobalTimeout.create(1000L));
        return preBidRequestContextBuilderCustomizer.apply(preBidRequestContextBuilderMinimal).build();
    }

    private static String givenBidResponse(
            Function<BidResponseBuilder, BidResponseBuilder> bidResponseBuilderCustomizer,
            Function<SeatBidBuilder, SeatBidBuilder> seatBidBuilderCustomizer,
            List<Function<BidBuilder, BidBuilder>> bidBuilderCustomizers) throws JsonProcessingException {

        // bid
        final BidBuilder bidBuilderMinimal = com.iab.openrtb.response.Bid.builder();
        final List<Bid> bids = bidBuilderCustomizers.stream()
                .map(bidBuilderBidBuilderFunction -> bidBuilderBidBuilderFunction.apply(bidBuilderMinimal).build())
                .collect(Collectors.toList());

        // seatBid
        final SeatBidBuilder seatBidBuilderMinimal = SeatBid.builder().bid(bids);
        final SeatBid seatBid = seatBidBuilderCustomizer.apply(seatBidBuilderMinimal).build();

        // bidResponse
        final BidResponseBuilder bidResponseBuilderMinimal = BidResponse.builder()
                .seatbid(singletonList(seatBid));
        final BidResponse bidResponse = bidResponseBuilderCustomizer.apply(bidResponseBuilderMinimal).build();

        return mapper.writeValueAsString(bidResponse);
    }

    private void givenHttpClientProducesException(Throwable throwable) {
        final HttpClientResponse httpClientResponse = givenHttpClientResponse(200);
        given(httpClientResponse.bodyHandler(any())).willReturn(httpClientResponse);
        given(httpClientResponse.exceptionHandler(any())).willAnswer(withSelfAndPassObjectToHandler(throwable));
    }

    private void givenHttpClientReturnsResponses(int statusCode, String... bidResponses) {
        final HttpClientResponse httpClientResponse = givenHttpClientResponse(statusCode);

        // setup multiple answers
        BDDMockito.BDDMyOngoingStubbing<HttpClientResponse> currentStubbing =
                given(httpClientResponse.bodyHandler(any()));
        for (String bidResponse : bidResponses) {
            currentStubbing = currentStubbing.willAnswer(withSelfAndPassObjectToHandler(Buffer.buffer(bidResponse)));
        }
    }

    private HttpClientResponse givenHttpClientResponse(int statusCode) {
        final HttpClientResponse httpClientResponse = mock(HttpClientResponse.class);
        given(httpClient.postAbs(anyString(), any()))
                .willAnswer(withRequestAndPassResponseToHandler(httpClientResponse));
        given(httpClientResponse.statusCode()).willReturn(statusCode);
        return httpClientResponse;
    }

    @SuppressWarnings("unchecked")
    private Answer<Object> withRequestAndPassResponseToHandler(HttpClientResponse httpClientResponse) {
        return inv -> {
            // invoking passed HttpClientResponse handler right away passing mock response to it
            ((Handler<HttpClientResponse>) inv.getArgument(1)).handle(httpClientResponse);
            return httpClientRequest;
        };
    }

    @SuppressWarnings("unchecked")
    private static <T> Answer<Object> withSelfAndPassObjectToHandler(T obj) {
        return inv -> {
            ((Handler<T>) inv.getArgument(0)).handle(obj);
            return inv.getMock();
        };
    }
}