package piuk.blockchain.android.ui.send;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.databinding.DataBindingUtil;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.Handler;
import android.support.annotation.ColorRes;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.annotation.StringRes;
import android.support.v4.app.Fragment;
import android.support.v4.content.ContextCompat;
import android.support.v4.content.LocalBroadcastManager;
import android.support.v7.app.AlertDialog;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.AppCompatEditText;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.text.method.DigitsKeyListener;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.EditText;
import android.widget.RelativeLayout;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jakewharton.rxbinding2.widget.RxTextView;

import info.blockchain.wallet.contacts.data.Contact;
import info.blockchain.wallet.payload.data.Account;
import info.blockchain.wallet.payload.data.LegacyAddress;

import org.apache.commons.lang3.StringUtils;

import java.io.IOException;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.text.NumberFormat;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import io.reactivex.android.schedulers.AndroidSchedulers;
import piuk.blockchain.android.R;
import piuk.blockchain.android.data.access.AccessState;
import piuk.blockchain.android.data.connectivity.ConnectivityStatus;
import piuk.blockchain.android.data.contacts.PaymentRequestType;
import piuk.blockchain.android.data.rxjava.IgnorableDefaultObserver;
import piuk.blockchain.android.data.services.EventService;
import piuk.blockchain.android.databinding.AlertWatchOnlySpendBinding;
import piuk.blockchain.android.databinding.FragmentSendBinding;
import piuk.blockchain.android.ui.account.ItemAccount;
import piuk.blockchain.android.ui.account.PaymentConfirmationDetails;
import piuk.blockchain.android.ui.account.SecondPasswordHandler;
import piuk.blockchain.android.ui.balance.BalanceFragment;
import piuk.blockchain.android.ui.base.BaseAuthActivity;
import piuk.blockchain.android.ui.chooser.AccountChooserActivity;
import piuk.blockchain.android.ui.confirm.ConfirmPaymentDialog;
import piuk.blockchain.android.ui.customviews.CustomKeypad;
import piuk.blockchain.android.ui.customviews.CustomKeypadCallback;
import piuk.blockchain.android.ui.customviews.MaterialProgressDialog;
import piuk.blockchain.android.ui.customviews.ToastCustom;
import piuk.blockchain.android.ui.home.MainActivity;
import piuk.blockchain.android.ui.zxing.CaptureActivity;
import piuk.blockchain.android.util.AppRate;
import piuk.blockchain.android.util.AppUtil;
import piuk.blockchain.android.util.PermissionUtil;
import piuk.blockchain.android.util.annotations.Thunk;

import static android.databinding.DataBindingUtil.inflate;
import static piuk.blockchain.android.ui.chooser.AccountChooserActivity.EXTRA_SELECTED_ITEM;
import static piuk.blockchain.android.ui.chooser.AccountChooserActivity.EXTRA_SELECTED_OBJECT_TYPE;


public class SendFragment extends Fragment implements SendContract.DataListener, CustomKeypadCallback {

    private static final String TAG = SendFragment.class.getSimpleName();

    public static final String ARGUMENT_SCAN_DATA = "scan_data";
    public static final String ARGUMENT_SELECTED_ACCOUNT_POSITION = "selected_account_position";
    public static final String ARGUMENT_CONTACT_ID = "contact_id";
    public static final String ARGUMENT_CONTACT_MDID = "contact_mdid";
    public static final String ARGUMENT_FCTX_ID = "fctx_id";
    public static final String ARGUMENT_SCAN_DATA_ADDRESS_INPUT_ROUTE = "address_input_route";

    private static final int SCAN_URI = 2010;
    private static final int SCAN_PRIVX = 2011;
    private static final int COOL_DOWN_MILLIS = 2 * 1000;

    @Thunk FragmentSendBinding binding;
    @Thunk SendViewModel viewModel;
    @Thunk AlertDialog transactionSuccessDialog;
    @Thunk boolean textChangeAllowed = true;
    private OnSendFragmentInteractionListener listener;
    private CustomKeypad customKeypad;
    private MaterialProgressDialog progressDialog;
    private AlertDialog largeTxWarning;
    private ConfirmPaymentDialog confirmPaymentDialog;

    private int selectedAccountPosition = -1;
    private long backPressed;
    private final Handler dialogHandler = new Handler();
    private final Runnable dialogRunnable = new Runnable() {
        @Override
        public void run() {
            if (transactionSuccessDialog != null && transactionSuccessDialog.isShowing()) {
                transactionSuccessDialog.dismiss();
            }
        }
    };

    private BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(final Context context, final Intent intent) {
            if (intent.getAction().equals(BalanceFragment.ACTION_INTENT) && binding != null) {
                viewModel.updateUI();
            }
        }
    };

    public SendFragment() {
        // Required empty public constructor
    }

    public static SendFragment newInstance(@Nullable String scanData,
                                           String scanRoute,
                                           int selectedAccountPosition) {
        SendFragment fragment = new SendFragment();
        Bundle args = new Bundle();
        args.putString(ARGUMENT_SCAN_DATA, scanData);
        args.putString(ARGUMENT_SCAN_DATA_ADDRESS_INPUT_ROUTE, scanRoute);
        args.putInt(ARGUMENT_SELECTED_ACCOUNT_POSITION, selectedAccountPosition);
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(true);

        if (getArguments() != null) {
            selectedAccountPosition = getArguments().getInt(ARGUMENT_SELECTED_ACCOUNT_POSITION);
        }
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        binding = DataBindingUtil.inflate(inflater, R.layout.fragment_send, container, false);
        viewModel = new SendViewModel(this, Locale.getDefault());

        getActivity().getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);

        setCustomKeypad();
        setupViews();
        setupFeesView();
        viewModel.onViewReady();

        return binding.getRoot();
    }

    @Override
    public Bundle getFragmentBundle() {
        return getArguments();
    }

    @Override
    public void onResume() {
        super.onResume();
        setupToolbar();
        closeKeypad();
        IntentFilter filter = new IntentFilter(BalanceFragment.ACTION_INTENT);
        LocalBroadcastManager.getInstance(getActivity()).registerReceiver(receiver, filter);
        viewModel.updateUI();
    }

    @Override
    public void onPause() {
        LocalBroadcastManager.getInstance(getActivity()).unregisterReceiver(receiver);
        super.onPause();
    }

    private void setupToolbar() {
        if (((AppCompatActivity) getActivity()).getSupportActionBar() != null) {
            ((BaseAuthActivity) getActivity()).setupToolbar(
                    ((MainActivity) getActivity()).getSupportActionBar(), R.string.send_bitcoin);
        } else {
            finishPage();
        }
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        if (menu != null) menu.clear();
        inflater.inflate(R.menu.menu_send, menu);
        super.onCreateOptionsMenu(menu, inflater);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.action_qr:
                if (ContextCompat.checkSelfPermission(getActivity(), Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                    PermissionUtil.requestCameraPermissionFromFragment(binding.getRoot(), this);
                } else {
                    startScanActivity(SCAN_URI);
                }
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    @SuppressWarnings({"ConstantConditions", "unchecked"})
    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (resultCode == Activity.RESULT_OK && requestCode == SCAN_URI
                && data != null && data.getStringExtra(CaptureActivity.SCAN_RESULT) != null) {

            viewModel.handleIncomingQRScan(data.getStringExtra(CaptureActivity.SCAN_RESULT),
                    EventService.EVENT_TX_INPUT_FROM_QR);

        } else if (requestCode == SCAN_PRIVX && resultCode == Activity.RESULT_OK) {
            final String scanData = data.getStringExtra(CaptureActivity.SCAN_RESULT);
            viewModel.handleScannedDataForWatchOnlySpend(scanData);

            // Set Receiving account
        } else if (resultCode == Activity.RESULT_OK
                && requestCode == AccountChooserActivity.REQUEST_CODE_CHOOSE_RECEIVING_ACCOUNT_FROM_SEND
                && data != null) {

            try {
                Class type = Class.forName(data.getStringExtra(EXTRA_SELECTED_OBJECT_TYPE));
                Object object = new ObjectMapper().readValue(data.getStringExtra(EXTRA_SELECTED_ITEM), type);

                if (object instanceof Contact) {
                    viewModel.setContact(((Contact) object));
                } else if (object instanceof Account) {
                    Account account = ((Account) object);
                    viewModel.setReceivingAddress(new ItemAccount(account.getLabel(), null, null, null, account));

                    String label = account.getLabel();
                    if (label == null || label.isEmpty()) {
                        label = account.getXpub();
                    }
                    binding.destination.setText(StringUtils.abbreviate(label, 32));
                } else if (object instanceof LegacyAddress) {
                    LegacyAddress legacyAddress = ((LegacyAddress) object);
                    viewModel.setReceivingAddress(new ItemAccount(legacyAddress.getLabel(), null, null, null, legacyAddress));

                    String label = legacyAddress.getLabel();
                    if (label == null || label.isEmpty()) {
                        label = legacyAddress.getAddress();
                    }
                    binding.destination.setText(StringUtils.abbreviate(label, 32));
                }

            } catch (ClassNotFoundException | IOException e) {
                throw new RuntimeException(e);
            }
            // Set Sending account
        } else if (resultCode == Activity.RESULT_OK
                && requestCode == AccountChooserActivity.REQUEST_CODE_CHOOSE_SENDING_ACCOUNT_FROM_SEND
                && data != null) {

            try {
                Class type = Class.forName(data.getStringExtra(EXTRA_SELECTED_OBJECT_TYPE));
                Object object = new ObjectMapper().readValue(data.getStringExtra(EXTRA_SELECTED_ITEM), type);

                ItemAccount chosenItem = null;
                if (object instanceof Account) {
                    Account account = ((Account) object);
                    chosenItem = new ItemAccount(account.getLabel(), null, null, null, account);

                    String label = chosenItem.label;
                    if (label == null || label.isEmpty()) {
                        label = account.getXpub();
                    }
                    binding.from.setText(StringUtils.abbreviate(label, 32));

                } else if (object instanceof LegacyAddress) {
                    LegacyAddress legacyAddress = ((LegacyAddress) object);
                    chosenItem = new ItemAccount(legacyAddress.getLabel(), null, null, null, legacyAddress);

                    String label = chosenItem.label;
                    if (label == null || label.isEmpty()) {
                        label = legacyAddress.getAddress();
                    }
                    binding.from.setText(StringUtils.abbreviate(label, 32));
                }

                viewModel.setSendingAddress(chosenItem);

                updateTotals(chosenItem);

            } catch (ClassNotFoundException | IOException e) {
                throw new RuntimeException(e);
            }
        }
    }

    public boolean isKeyboardVisible() {
        return customKeypad.isVisible();
    }

    public void onBackPressed() {
        if (isKeyboardVisible()) {
            closeKeypad();
        } else {
            handleBackPressed();
        }
    }

    public void handleBackPressed() {
        if (backPressed + COOL_DOWN_MILLIS > System.currentTimeMillis()) {
            AccessState.getInstance().logout(getContext());
            return;
        } else {
            onExitConfirmToast();
        }

        backPressed = System.currentTimeMillis();
    }

    public void onExitConfirmToast() {
        showToast(R.string.exit_confirm, ToastCustom.TYPE_GENERAL);
    }

    @SuppressWarnings("StatementWithEmptyBody")
    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        if (requestCode == PermissionUtil.PERMISSION_REQUEST_CAMERA) {
            if (grantResults.length == 1 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startScanActivity(SCAN_URI);
            } else {
                // Permission request was denied.
            }
        } else {
            super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        }
    }

    private void closeKeypad() {
        customKeypad.setNumpadVisibility(View.GONE);
    }

    @Override
    public void onKeypadClose() {
        // Show bottom nav if applicable
        if (getActivity() instanceof MainActivity) {
            ((MainActivity) getActivity()).getBottomNavigationView().restoreBottomNavigation();
        }
        // Resize activity back to initial state
        RelativeLayout.LayoutParams layoutParams = new RelativeLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT);

        layoutParams.addRule(RelativeLayout.ABOVE, 0);
        binding.scrollView.setLayoutParams(layoutParams);
    }

    @Override
    public void onKeypadOpen() {
        // Hide bottom nav if applicable
        if (getActivity() instanceof MainActivity) {
            ((MainActivity) getActivity()).getBottomNavigationView().hideBottomNavigation();
        }
    }

    @Override
    public void onKeypadOpenCompleted() {
        // Resize activity around keyboard view
        RelativeLayout.LayoutParams layoutParams = new RelativeLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT);

        layoutParams.addRule(RelativeLayout.ABOVE, R.id.keyboard);

        binding.scrollView.setLayoutParams(layoutParams);
    }

    private void startScanActivity(int code) {
        if (!new AppUtil(getActivity()).isCameraOpen()) {
            Intent intent = new Intent(getActivity(), CaptureActivity.class);
            startActivityForResult(intent, code);
        } else {
            showToast(R.string.camera_unavailable, ToastCustom.TYPE_ERROR);
        }
    }

    private void setCustomKeypad() {
        customKeypad = binding.keyboard;
        customKeypad.setCallback(this);
        customKeypad.setDecimalSeparator(getDefaultDecimalSeparator());

        //Enable custom keypad and disables default keyboard from popping up
        customKeypad.enableOnView(binding.amountRow.amountBtc);
        customKeypad.enableOnView(binding.amountRow.amountFiat);
        customKeypad.enableOnView(binding.edittextCustomFee);

        binding.amountRow.amountBtc.setText("");
        binding.amountRow.amountBtc.requestFocus();
    }

    private void setupViews() {
        setupDestinationView();
        setupSendFromView();
        setupReceiveToView();

        setupBtcTextField();
        setupFiatTextField();

        binding.max.setOnClickListener(view ->
                viewModel.spendAllClicked(viewModel.getSendingItemAccount(), getFeePriority()));

        binding.buttonSend.setOnClickListener(v -> {
            customKeypad.setNumpadVisibility(View.GONE);
            if (ConnectivityStatus.hasConnectivity(getActivity())) {
                requestSendPayment();
            } else {
                showToast(R.string.check_connectivity_exit, ToastCustom.TYPE_ERROR);
            }
        });
    }

    private void setupFeesView() {
        FeePriorityAdapter adapter = new FeePriorityAdapter(getActivity(),
                viewModel.getFeeOptionsForDropDown());

        binding.spinnerPriority.setAdapter(adapter);

        binding.spinnerPriority.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                switch (position) {
                    case 0:
                    case 1:
                        binding.buttonSend.setEnabled(true);
                        updateTotals(viewModel.getSendingItemAccount());
                        binding.textviewFeeAbsolute.setVisibility(View.VISIBLE);
                        binding.textviewFeeTime.setVisibility(View.VISIBLE);
                        binding.textInputLayout.setVisibility(View.GONE);
                        break;
                    case 2:
                        if (viewModel.shouldShowAdvancedFeeWarning()) {
                            alertCustomSpend();
                        } else {
                            displayCustomFeeField();
                        }
                        break;
                }

                DisplayFeeOptions options = viewModel.getFeeOptionsForDropDown().get(position);
                binding.textviewFeeType.setText(options.getTitle());
                binding.textviewFeeTime.setText(position != 2 ? options.getDescription() : null);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                // No-op
            }
        });

        binding.textviewFeeAbsolute.setOnClickListener(v -> binding.spinnerPriority.performClick());
        binding.textviewFeeType.setText(R.string.fee_options_regular);
        binding.textviewFeeTime.setText(R.string.fee_options_regular_time);

        RxTextView.textChanges(binding.amountRow.amountBtc)
                .debounce(400, TimeUnit.MILLISECONDS)
                .subscribeOn(AndroidSchedulers.mainThread())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                        value -> updateTotals(viewModel.getSendingItemAccount()),
                        Throwable::printStackTrace);

        RxTextView.textChanges(binding.amountRow.amountFiat)
                .debounce(400, TimeUnit.MILLISECONDS)
                .subscribeOn(AndroidSchedulers.mainThread())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                        value -> updateTotals(viewModel.getSendingItemAccount()),
                        Throwable::printStackTrace);
    }

    private void requestSendPayment() {
        viewModel.onSendClicked(
                binding.amountRow.amountBtc.getText().toString(),
                binding.destination.getText().toString(),
                getFeePriority());
    }

    private void setupDestinationView() {
        binding.destination.setHorizontallyScrolling(false);
        binding.destination.setLines(3);

        //Avoid OntouchListener - causes paste issues on some Samsung devices
        binding.destination.setOnClickListener(v -> {
            binding.destination.setText("");
            viewModel.setReceivingAddress(null);
        });
        //LongClick listener required to clear receive address in memory when user long clicks to paste
        binding.destination.setOnLongClickListener(v -> {
            binding.destination.setText("");
            viewModel.setReceivingAddress(null);
            v.performClick();
            return false;
        });

        //TextChanged listener required to invalidate receive address in memory when user
        //chooses to edit address populated via QR
        RxTextView.textChanges(binding.destination)
                .doOnNext(ignored -> {
                    if (getActivity().getCurrentFocus() == binding.destination) {
                        viewModel.setReceivingAddress(null);
                    }
                })
                .subscribe(new IgnorableDefaultObserver<>());

        binding.destination.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus && customKeypad != null) {
                customKeypad.setNumpadVisibility(View.GONE);
            }
        });
    }

    private void setupSendFromView() {
        ItemAccount itemAccount;
        if (selectedAccountPosition != -1) {
            itemAccount = viewModel.getAddressList(getFeePriority()).get(selectedAccountPosition);
        } else {
            itemAccount = viewModel.getAddressList(getFeePriority()).get(viewModel.getDefaultAccount());
        }

        viewModel.setSendingAddress(itemAccount);
        updateTotals(itemAccount);
        binding.from.setText(itemAccount.label);

        binding.from.setOnClickListener(v -> startFromFragment());
        binding.imageviewDropdownSend.setOnClickListener(v -> startFromFragment());
    }

    @Thunk
    void updateTotals(ItemAccount itemAccount) {
        viewModel.calculateTransactionAmounts(itemAccount,
                binding.amountRow.amountBtc.getText().toString(),
                getFeePriority(),
                null);
    }

    @FeeType.FeePriorityDef
    private int getFeePriority() {
        int position = binding.spinnerPriority.getSelectedItemPosition();
        switch (position) {
            case 1:
                return FeeType.FEE_OPTION_PRIORITY;
            case 2:
                return FeeType.FEE_OPTION_CUSTOM;
            case 0:
            default:
                return FeeType.FEE_OPTION_REGULAR;
        }
    }

    private void startFromFragment() {
        AccountChooserActivity.startForResult(this,
                AccountChooserActivity.REQUEST_CODE_CHOOSE_SENDING_ACCOUNT_FROM_SEND,
                PaymentRequestType.REQUEST);
    }

    private void setupReceiveToView() {
        binding.imageviewDropdownReceive.setOnClickListener(v ->
                AccountChooserActivity.startForResult(this,
                        AccountChooserActivity.REQUEST_CODE_CHOOSE_RECEIVING_ACCOUNT_FROM_SEND,
                        PaymentRequestType.SEND));
    }

    @Override
    public void hideSendingAddressField() {
        binding.fromRow.setVisibility(View.GONE);
    }

    @Override
    public void hideReceivingAddressField() {
        binding.destination.setHint(R.string.to_field_helper_no_dropdown);
    }

    @Override
    public void showInvalidAmount() {
        showToast(R.string.invalid_amount, ToastCustom.TYPE_ERROR);
    }

    @Override
    public void updateBtcAmount(String amount) {
        binding.amountRow.amountBtc.setText(amount);
        binding.amountRow.amountBtc.setSelection(binding.amountRow.amountBtc.getText().length());
    }

    @Override
    public void setDestinationAddress(String btcAddress) {
        binding.destination.setText(btcAddress);
    }

    @Override
    public void setMaxAvailable(String max) {
        binding.max.setText(max);
    }

    @Override
    public void setUnconfirmedFunds(String warning) {
        binding.unconfirmedFundsWarning.setText(warning);
    }

    @Override
    public void setContactName(String name) {
        binding.destination.setText(name);
    }

    @Override
    public void setMaxAvailableVisible(boolean visible) {
        if (visible) {
            binding.max.setVisibility(View.VISIBLE);
            binding.progressBarMaxAvailable.setVisibility(View.INVISIBLE);
        } else {
            binding.max.setVisibility(View.INVISIBLE);
            binding.progressBarMaxAvailable.setVisibility(View.VISIBLE);
        }
    }

    @Override
    public void setMaxAvailableColor(@ColorRes int color) {
        binding.max.setTextColor(ContextCompat.getColor(getContext(), color));
    }

    @Override
    public void updateBtcUnit(String unit) {
        binding.amountRow.currencyBtc.setText(unit);
    }

    @Override
    public void updateFiatUnit(String unit) {
        binding.amountRow.currencyFiat.setText(unit);
    }

    @Override
    public void onSetSpendAllAmount(String textFromSatoshis) {
        binding.amountRow.amountBtc.setText(textFromSatoshis);
    }

    @Override
    public void showToast(@StringRes int message, @ToastCustom.ToastType String toastType) {
        ToastCustom.makeText(getActivity(), getString(message), ToastCustom.LENGTH_SHORT, toastType);
    }

    @Override
    public void showSecondPasswordDialog() {
        new SecondPasswordHandler(getContext()).validate(new SecondPasswordHandler.ResultListener() {
            @Override
            public void onNoSecondPassword() {
                viewModel.onNoSecondPassword();
            }

            @Override
            public void onSecondPasswordValidated(String validateSecondPassword) {
                viewModel.onSecondPasswordValidated(validateSecondPassword);
            }
        });
    }

    @Override
    public void onShowTransactionSuccess(String hash, long transactionValue) {
        playAudio();

        AlertDialog.Builder dialogBuilder = new AlertDialog.Builder(getActivity());
        View dialogView = View.inflate(getActivity(), R.layout.modal_transaction_success, null);
        transactionSuccessDialog = dialogBuilder.setView(dialogView)
                .setPositiveButton(getString(R.string.done), null)
                .create();
        transactionSuccessDialog.setTitle(R.string.transaction_submitted);

        AppRate appRate = new AppRate(getActivity())
                .setMinTransactionsUntilPrompt(3)
                .incrementTransactionCount();

        // If should show app rate, success dialog shows first and launches
        // rate dialog on dismiss. Dismissing rate dialog then closes the page. This will
        // happen if the user chooses to rate the app - they'll return to the main page.
        if (appRate.shouldShowDialog()) {
            AlertDialog ratingDialog = appRate.getRateDialog();
            ratingDialog.setOnDismissListener(d -> finishPage());
            transactionSuccessDialog.show();
            transactionSuccessDialog.setOnDismissListener(d -> ratingDialog.show());
        } else {
            transactionSuccessDialog.show();
            transactionSuccessDialog.setOnDismissListener(dialogInterface -> finishPage());
        }

        dialogHandler.postDelayed(dialogRunnable, 5 * 1000);
    }

    @Override
    public void onShowBIP38PassphrasePrompt(String scanData) {
        final AppCompatEditText password = new AppCompatEditText(getActivity());
        password.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        password.setHint(R.string.password);

        new AlertDialog.Builder(getActivity(), R.style.AlertDialogStyle)
                .setTitle(R.string.app_name)
                .setMessage(R.string.bip38_password_entry)
                .setView(password)
                .setCancelable(false)
                .setPositiveButton(android.R.string.ok, (dialog, whichButton) ->
                        viewModel.spendFromWatchOnlyBIP38(password.getText().toString(), scanData))
                .setNegativeButton(android.R.string.cancel, null).show();
    }

    private void onShowLargeTransactionWarning() {
        if (largeTxWarning != null && largeTxWarning.isShowing()) {
            largeTxWarning.dismiss();
        }

        largeTxWarning = new AlertDialog.Builder(getActivity(), R.style.AlertDialogStyle)
                .setCancelable(false)
                .setTitle(R.string.warning)
                .setMessage(R.string.large_tx_warning)
                .setPositiveButton(android.R.string.ok, null)
                .create();

        largeTxWarning.show();
    }

    @Override
    public void onShowSpendFromWatchOnly(String address) {
        new AlertDialog.Builder(getActivity(), R.style.AlertDialogStyle)
                .setTitle(R.string.privx_required)
                .setMessage(String.format(getString(R.string.watch_only_spend_instructionss), address))
                .setCancelable(false)
                .setPositiveButton(R.string.dialog_continue, (dialog, whichButton) -> {
                    if (ContextCompat.checkSelfPermission(getActivity(), Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                        PermissionUtil.requestCameraPermissionFromActivity(binding.getRoot(), getActivity());
                    } else {
                        startScanActivity(SCAN_PRIVX);
                    }
                })
                .setNegativeButton(android.R.string.cancel, null).show();
    }

    @Override
    public void navigateToAddNote(String contactId, PaymentRequestType paymentRequestType, long satoshis) {
        if (listener != null) {
            listener.onTransactionNotesRequested(contactId, null, paymentRequestType, satoshis);
        }
    }

    // BTC Field
    private void setupBtcTextField() {
        binding.amountRow.amountBtc.setSelectAllOnFocus(true);
        binding.amountRow.amountBtc.setHint("0" + getDefaultDecimalSeparator() + "00");

        binding.amountRow.amountBtc.setKeyListener(
                DigitsKeyListener.getInstance("0123456789" + getDefaultDecimalSeparator()));
        binding.amountRow.amountBtc.addTextChangedListener(btcTextWatcher);
    }

    // Fiat Field
    private void setupFiatTextField() {
        binding.amountRow.amountFiat.setHint("0" + getDefaultDecimalSeparator() + "00");
        binding.amountRow.amountFiat.setSelectAllOnFocus(true);

        binding.amountRow.amountFiat.setKeyListener(
                DigitsKeyListener.getInstance("0123456789" + getDefaultDecimalSeparator()));
        binding.amountRow.amountFiat.addTextChangedListener(fiatTextWatcher);
    }

    @Override
    public void updateBtcTextField(String text) {
        binding.amountRow.amountBtc.setText(text);
    }

    @Override
    public void updateFiatTextField(String text) {
        binding.amountRow.amountFiat.setText(text);
    }

    @Thunk
    void setKeyListener(Editable s, EditText editText) {
        if (s.toString().contains(getDefaultDecimalSeparator())) {
            editText.setKeyListener(DigitsKeyListener.getInstance("0123456789"));
        } else {
            editText.setKeyListener(DigitsKeyListener.getInstance("0123456789" + getDefaultDecimalSeparator()));
        }
    }

    @Thunk
    Editable formatEditable(Editable s, String input, int maxLength, EditText editText) {
        try {
            if (input.contains(getDefaultDecimalSeparator())) {
                String dec = input.substring(input.indexOf(getDefaultDecimalSeparator()));
                if (!dec.isEmpty()) {
                    dec = dec.substring(1);
                    if (dec.length() > maxLength) {
                        editText.setText(input.substring(0, input.length() - 1));
                        editText.setSelection(editText.getText().length());
                        s = editText.getEditableText();
                    }
                }
            }
        } catch (NumberFormatException e) {
            Log.e(TAG, "afterTextChanged: ", e);
        }
        return s;
    }

    private TextWatcher btcTextWatcher = new TextWatcher() {
        @Override
        public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            // No-op
        }

        @Override
        public void onTextChanged(CharSequence s, int start, int before, int count) {
            // No-op
        }

        @Override
        public void afterTextChanged(Editable s) {
            String input = s.toString();

            binding.amountRow.amountBtc.removeTextChangedListener(this);
            NumberFormat btcFormat = NumberFormat.getInstance(Locale.getDefault());
            btcFormat.setMaximumFractionDigits(viewModel.getCurrencyHelper().getMaxBtcDecimalLength() + 1);
            btcFormat.setMinimumFractionDigits(0);

            s = formatEditable(s, input, viewModel.getCurrencyHelper().getMaxBtcDecimalLength(), binding.amountRow.amountBtc);

            binding.amountRow.amountBtc.addTextChangedListener(this);

            if (textChangeAllowed) {
                textChangeAllowed = false;
                viewModel.updateFiatTextField(s.toString());

                textChangeAllowed = true;
            }
            setKeyListener(s, binding.amountRow.amountBtc);
        }
    };

    private TextWatcher fiatTextWatcher = new TextWatcher() {
        @Override
        public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            // No-op
        }

        @Override
        public void onTextChanged(CharSequence s, int start, int before, int count) {
            // No-op
        }

        @Override
        public void afterTextChanged(Editable s) {
            String input = s.toString();

            binding.amountRow.amountFiat.removeTextChangedListener(this);
            int maxLength = 2;
            NumberFormat fiatFormat = NumberFormat.getInstance(Locale.getDefault());
            fiatFormat.setMaximumFractionDigits(maxLength + 1);
            fiatFormat.setMinimumFractionDigits(0);

            s = formatEditable(s, input, maxLength, binding.amountRow.amountFiat);

            binding.amountRow.amountFiat.addTextChangedListener(this);

            if (textChangeAllowed) {
                textChangeAllowed = false;
                viewModel.updateBtcTextField(s.toString());
                textChangeAllowed = true;
            }
            setKeyListener(s, binding.amountRow.amountFiat);
        }
    };

    private String getDefaultDecimalSeparator() {
        DecimalFormat format = (DecimalFormat) DecimalFormat.getInstance(Locale.getDefault());
        DecimalFormatSymbols symbols = format.getDecimalFormatSymbols();
        return Character.toString(symbols.getDecimalSeparator());
    }

    @Override
    public void onShowPaymentDetails(PaymentConfirmationDetails details) {
        confirmPaymentDialog = ConfirmPaymentDialog.newInstance(details, true);
        confirmPaymentDialog
                .show(getFragmentManager(), ConfirmPaymentDialog.class.getSimpleName());

        if (viewModel.isLargeTransaction()) {
            binding.getRoot().postDelayed(this::onShowLargeTransactionWarning, 500);
        }
    }

    @Override
    public void onShowReceiveToWatchOnlyWarning(String address) {
        AlertDialog.Builder dialogBuilder = new AlertDialog.Builder(getActivity(), R.style.AlertDialogStyle);
        AlertWatchOnlySpendBinding dialogBinding = inflate(LayoutInflater.from(getActivity()),
                R.layout.alert_watch_only_spend, null, false);
        dialogBuilder.setView(dialogBinding.getRoot());
        dialogBuilder.setCancelable(false);

        final AlertDialog alertDialog = dialogBuilder.create();
        alertDialog.setCanceledOnTouchOutside(false);

        dialogBinding.confirmCancel.setOnClickListener(v -> {
            binding.destination.setText("");
            if (dialogBinding.confirmDontAskAgain.isChecked())
                viewModel.disableWatchOnlySpendWarning();
            alertDialog.dismiss();
        });

        dialogBinding.confirmContinue.setOnClickListener(v -> {
            binding.destination.setText(address);
            if (dialogBinding.confirmDontAskAgain.isChecked())
                viewModel.disableWatchOnlySpendWarning();
            alertDialog.dismiss();
        });

        alertDialog.show();
    }

    private void playAudio() {
        AudioManager audioManager = (AudioManager) getActivity().getSystemService(Context.AUDIO_SERVICE);
        if (audioManager != null && audioManager.getRingerMode() == AudioManager.RINGER_MODE_NORMAL) {
            MediaPlayer mp;
            mp = MediaPlayer.create(getActivity().getApplicationContext(), R.raw.beep);
            mp.setOnCompletionListener(mp1 -> {
                mp1.reset();
                mp1.release();
            });
            mp.start();
        }
    }

    @Nullable
    @Override
    public String getClipboardContents() {
        ClipboardManager clipMan = (ClipboardManager) getActivity().getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData clip = clipMan.getPrimaryClip();
        if (clip != null && clip.getItemCount() > 0) {
            return clip.getItemAt(0).coerceToText(getActivity()).toString();
        }
        return null;
    }

    @Override
    public void showProgressDialog() {
        progressDialog = new MaterialProgressDialog(getActivity());
        progressDialog.setCancelable(false);
        progressDialog.setMessage(R.string.please_wait);
        progressDialog.show();
    }

    @Override
    public void dismissProgressDialog() {
        if (progressDialog != null) {
            progressDialog.dismiss();
            progressDialog = null;
        }
    }

    @Override
    public void finishPage() {
        if (listener != null) listener.onSendFragmentClose();
    }

    public void onChangeFeeClicked() {
        confirmPaymentDialog.dismiss();
    }

    @Thunk
    void alertCustomSpend() {
        new AlertDialog.Builder(getActivity(), R.style.AlertDialogStyle)
                .setTitle(R.string.transaction_fee)
                .setMessage(R.string.fee_options_advanced_warning)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    viewModel.disableAdvancedFeeWarning();
                    displayCustomFeeField();
                })
                .setNegativeButton(android.R.string.cancel, (dialog, which) ->
                        binding.spinnerPriority.setSelection(0))
                .show();
    }

    @Thunk
    void displayCustomFeeField() {
        binding.textviewFeeAbsolute.setVisibility(View.GONE);
        binding.textviewFeeTime.setVisibility(View.GONE);
        binding.textInputLayout.setVisibility(View.VISIBLE);
        binding.buttonSend.setEnabled(false);
        binding.textInputLayout.setHint(getString(R.string.fee_options_sat_byte_hint));

        binding.edittextCustomFee.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus || !binding.edittextCustomFee.getText().toString().isEmpty()) {
                binding.textInputLayout.setHint(getString(R.string.fee_options_sat_byte_inline_hint,
                        String.valueOf(viewModel.getFeeOptions().getRegularFee()),
                        String.valueOf(viewModel.getFeeOptions().getPriorityFee())));
            } else if (binding.edittextCustomFee.getText().toString().isEmpty()) {
                binding.textInputLayout.setHint(getString(R.string.fee_options_sat_byte_hint));
            } else {
                binding.textInputLayout.setHint(getString(R.string.fee_options_sat_byte_hint));
            }
        });

        RxTextView.textChanges(binding.edittextCustomFee)
                .map(CharSequence::toString)
                .doOnNext(value -> binding.buttonSend.setEnabled(!value.isEmpty() && !value.equals("0")))
                .filter(value -> !value.isEmpty())
                .map(Long::valueOf)
                .onErrorReturnItem(0L)
                .doOnNext(value -> {
                    if (value < viewModel.getFeeOptions().getLimits().getMin()) {
                        binding.textInputLayout.setError(getString(R.string.fee_options_fee_too_low));
                    } else if (value > viewModel.getFeeOptions().getLimits().getMax()) {
                        binding.textInputLayout.setError(getString(R.string.fee_options_fee_too_high));
                    } else {
                        binding.textInputLayout.setError(null);
                    }
                })
                .debounce(300, TimeUnit.MILLISECONDS)
                .subscribeOn(AndroidSchedulers.mainThread())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                        value -> updateTotals(viewModel.getSendingItemAccount()),
                        Throwable::printStackTrace);
    }

    @Override
    public long getCustomFeeValue() {
        String amount = binding.edittextCustomFee.getText().toString();
        return !amount.isEmpty() ? Long.valueOf(amount) : 0;
    }

    @Override
    public void updateFeeField(String fee) {
        binding.textviewFeeAbsolute.setText(fee);
    }

    public void onSendClicked() {
        if (ConnectivityStatus.hasConnectivity(getActivity())) {
            viewModel.submitPayment();
        } else {
            showToast(R.string.check_connectivity_exit, ToastCustom.TYPE_ERROR);
        }
    }

    @Override
    public void dismissConfirmationDialog() {
        confirmPaymentDialog.dismiss();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        viewModel.destroy();
    }

    @Override
    public void onAttach(Context context) {
        super.onAttach(context);
        if (context instanceof OnSendFragmentInteractionListener) {
            listener = (OnSendFragmentInteractionListener) context;
        } else {
            throw new RuntimeException(context + " must implement OnSendFragmentInteractionListener");
        }
    }

    @Override
    public void onDetach() {
        super.onDetach();
        listener = null;
    }

    public interface OnSendFragmentInteractionListener {

        void onSendFragmentClose();

        void onTransactionNotesRequested(String contactId,
                                         @Nullable Integer accountPosition,
                                         PaymentRequestType paymentRequestType,
                                         long satoshis);
    }
}
