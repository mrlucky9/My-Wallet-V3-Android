package piuk.blockchain.android.data.services;

import com.fasterxml.jackson.databind.ObjectMapper;

import info.blockchain.wallet.api.trade.coinify.CoinifyApi;
import info.blockchain.wallet.api.trade.coinify.data.CoinifyTrade;
import info.blockchain.wallet.api.trade.sfox.SFOXApi;
import info.blockchain.wallet.api.trade.sfox.data.SFOXTransaction;
import info.blockchain.wallet.metadata.Metadata;
import info.blockchain.wallet.metadata.MetadataNodeFactory;
import info.blockchain.wallet.payload.PayloadManager;
import info.blockchain.wallet.util.MetadataUtil;

import org.bitcoinj.crypto.DeterministicKey;
import org.spongycastle.util.encoders.Hex;

import java.util.ArrayList;
import java.util.List;

import io.reactivex.Observable;
import io.reactivex.subjects.ReplaySubject;
import piuk.blockchain.android.data.exchange.ExchangeData;
import piuk.blockchain.android.data.exchange.TradeData;
import piuk.blockchain.android.data.exchange.WebViewLoginDetails;
import piuk.blockchain.android.data.rxjava.RxBus;
import piuk.blockchain.android.data.rxjava.RxUtil;
import piuk.blockchain.android.data.websocket.WebSocketReceiveEvent;
import timber.log.Timber;

/**
 * Created by justin on 5/1/17.
 */

public class ExchangeService {

    private static final int METADATA_TYPE_EXCHANGE = 3;

    private PayloadManager payloadManager;
    private RxBus rxBus;

    private ReplaySubject<Metadata> metadataSubject;
    private CoinifyApi coinifyApi;
    private SFOXApi sfoxApi;
    private boolean didStartLoad;

    public ExchangeService(PayloadManager payloadManager,
                           RxBus rxBus,
                           CoinifyApi coinifyApi,
                           SFOXApi sfoxApi) {
        this.payloadManager = payloadManager;
        this.rxBus = rxBus;
        this.coinifyApi = coinifyApi;
        this.sfoxApi = sfoxApi;

        metadataSubject = ReplaySubject.create(1);
    }

    public void wipe() {
        metadataSubject = ReplaySubject.create(1);
        didStartLoad = false;
    }

    public Observable<WebViewLoginDetails> getWebViewLoginDetails() {
        return Observable.zip(
                getExchangeData().flatMap(buyMetadata -> Observable
                        .fromCallable(() -> {
                            String metadata = buyMetadata.getMetadata();
                            return metadata == null ? "" : metadata;
                        })
                        .compose(RxUtil.applySchedulersToObservable())
                ),
                getExchangeData().flatMap(buyMetadata -> Observable
                        .fromCallable(() -> {
                            buyMetadata.fetchMagic();
                            byte[] magicHash = buyMetadata.getMagicHash();
                            return magicHash == null ? "" : Hex.toHexString(magicHash);
                        })
                        .compose(RxUtil.applySchedulersToObservable())
                ),
                (externalJson, magicHash) -> {
                    String walletJson = payloadManager.getPayload().toJson();
                    String password = payloadManager.getTempPassword();
                    return new WebViewLoginDetails(walletJson, password, externalJson, magicHash);
                }
        );
    }

    private Observable<Metadata> getExchangeData() {
        if (!didStartLoad) {
            reloadExchangeData();
            didStartLoad = true;
        }
        return metadataSubject;
    }

    public Observable<String> watchPendingTrades() {
        Observable<WebSocketReceiveEvent> receiveEvents = rxBus.register(WebSocketReceiveEvent.class);

        return getPendingTradeAddresses()
                .doOnNext(address ->
                        Timber.d("watchPendingTrades: watching receive address: " + address))
                .flatMap(address -> receiveEvents
                        .filter(event -> event.getAddress().equals(address))
                        .map(WebSocketReceiveEvent::getHash));
    }

    private Observable<String> getPendingTradeAddresses() {
        return getExchangeData()
                .flatMap(metadata -> Observable
                        .fromCallable(() -> {
                            String exchangeData = metadata.getMetadata();
                            return exchangeData == null ? "" : exchangeData;
                        })
                        .compose(RxUtil.applySchedulersToObservable()))
                .flatMapIterable(exchangeData -> {

                    if (exchangeData.isEmpty()) return new ArrayList<>();

                    ObjectMapper mapper = new ObjectMapper();
                    ExchangeData data = mapper.readValue(exchangeData, ExchangeData.class);

                    List<TradeData> trades = new ArrayList<>();
                    if (data.getCoinify() != null) {
                        trades.addAll(data.getCoinify().getTrades());
                    } else if (data.getSfox() != null) {
                        trades.addAll(data.getSfox().getTrades());
                    }

                    return trades;
                })
                .filter(tradeData -> tradeData.isBuy() && !tradeData.isConfirmed())
                .map(tradeData ->
                        payloadManager.getReceiveAddressAtArbitraryPosition(
                                payloadManager.getPayload().getHdWallets().get(0).getAccount(tradeData.getAccountIndex()),
                                tradeData.getReceiveIndex()))
                .distinct();
    }

    public void reloadExchangeData() {
        MetadataNodeFactory metadataNodeFactory = payloadManager.getMetadataNodeFactory();
        DeterministicKey metadataNode = metadataNodeFactory.getMetadataNode();

        if (metadataNode != null) {
            Observable<Metadata> exchangeDataStream = getMetadata(metadataNode);
            exchangeDataStream.subscribeWith(metadataSubject);
        } else {
            Timber.e("MetadataNode not generated yet. Wallet possibly double encrypted.");
        }
    }

    private Observable<Metadata> getMetadata(DeterministicKey node) {
        return Observable.fromCallable(() -> {
            DeterministicKey metadataHDNode = MetadataUtil.deriveMetadataNode(node);
            return new Metadata.Builder(metadataHDNode, METADATA_TYPE_EXCHANGE).build();
        }).compose(RxUtil.applySchedulersToObservable());
    }

    private Observable<List<CoinifyTrade>> getCoinifyTrades(String accessToken) {
        return coinifyApi.getTrades(accessToken)
                .compose(RxUtil.applySchedulersToObservable());
    }

    public Observable<List<SFOXTransaction>> getSfoxTransactions(String accountToken) {
        return sfoxApi.getTransactions(accountToken)
                .compose(RxUtil.applySchedulersToObservable());
    }

    public Observable<Boolean> hasCoinifyAccount() {
        return getExchangeData()
                .flatMap(metadata -> Observable
                        .fromCallable(() -> {
                            String exchangeData = metadata.getMetadata();
                            return exchangeData == null ? "" : exchangeData;
                        })
                        .compose(RxUtil.applySchedulersToObservable()))
                .map(exchangeData -> {
                    if (exchangeData.isEmpty()) return false;

                    ObjectMapper mapper = new ObjectMapper();
                    ExchangeData data = mapper.readValue(exchangeData, ExchangeData.class);

                    return data.getCoinify().getUser() != 0;
                });
    }

}
