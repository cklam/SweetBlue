package com.idevicesinc.sweetblue;

import android.bluetooth.le.ScanSettings;
import com.idevicesinc.sweetblue.PA_StateTracker.E_Intent;
import com.idevicesinc.sweetblue.compat.L_Util;
import com.idevicesinc.sweetblue.compat.M_Util;
import com.idevicesinc.sweetblue.utils.Interval;
import com.idevicesinc.sweetblue.utils.Utils;
import java.util.List;


class P_Task_Scan extends PA_Task_RequiresBleOn
{
	static final int Mode_NULL			= -1;
	static final int Mode_BLE			=  0;
	static final int Mode_CLASSIC		=  1;

	private static final int CallbackType_UNKNOWN = -1;
	
	private int m_mode = Mode_NULL;
	
	//TODO
	private final boolean m_explicit = true;
	private final boolean m_isPoll;
	private final double m_scanTime;

	private final int m_retryCountMax = 3;

	private final L_Util.ScanCallback m_scanCallback_postLollipop = new L_Util.ScanCallback()
	{

		// Taken from android source.
		public static final int SCAN_FAILED_ALREADY_STARTED = 1;

		@Override public void onScanResult(final int callbackType, final L_Util.ScanResult result)
		{
			if( getManager().getUpdateLoop().postNeeded() )
			{
				getManager().getUpdateLoop().postIfNeeded(new Runnable()
				{
					@Override public void run()
					{
						onScanResult_mainThread(callbackType, result);
					}
				});
			}
			else
			{
				onScanResult_mainThread(callbackType, result);
			}
		}

		private void onScanResult_mainThread(final int callbackType, final L_Util.ScanResult result)
		{
			getManager().m_nativeStateTracker.append(BleManagerState.SCANNING, getIntent(), BleStatuses.GATT_STATUS_NOT_APPLICABLE);

			getManager().onDiscoveredFromNativeStack(result.getDevice(), result.getRssi(), result.getRecord());
		}

		@Override public void onBatchScanResults(final List<L_Util.ScanResult> results)
		{
			if( getManager().getUpdateLoop().postNeeded() )
			{
				getManager().getUpdateLoop().postIfNeeded(new Runnable()
				{
					@Override public void run()
					{
						onBatchScanResults_mainThread(results);
					}
				});
			}
			else
			{
				onBatchScanResults_mainThread(results);
			}
		}

		private void onBatchScanResults_mainThread(final List<L_Util.ScanResult> results)
		{
			if( results != null )
			{
				for( int i = 0; i < results.size(); i++ )
				{
					final L_Util.ScanResult result_ith = results.get(i);

					if( result_ith != null )
					{
						onScanResult_mainThread(CallbackType_UNKNOWN, result_ith);
					}
				}
			}
		}

		@Override public void onScanFailed(final int errorCode)
		{
			if( getManager().getUpdateLoop().postNeeded() )
			{
				getManager().getUpdateLoop().postIfNeeded(new Runnable()
				{
					@Override public void run()
					{
						onScanFailed_mainThread(errorCode);
					}
				});
			}
			else
			{
				onScanFailed_mainThread(errorCode);
			}
		}

		private void onScanFailed_mainThread(final int errorCode)
		{
			if( errorCode != SCAN_FAILED_ALREADY_STARTED )
			{
				fail();
			}
			else
			{
				tryClassicDiscovery(getIntent(), /*suppressUhOh=*/false);

				m_mode = Mode_CLASSIC;
			}
		}
	};

	public P_Task_Scan(BleManager manager, I_StateListener listener, double scanTime, boolean isPoll)
	{
		super(manager, listener);
		
		m_scanTime = scanTime;
		m_isPoll = isPoll;
	}

	public E_Intent getIntent()
	{
		return m_explicit ? E_Intent.INTENTIONAL : E_Intent.UNINTENTIONAL;
	}

	public L_Util.ScanCallback getScanCallback_postLollipop()
	{
		return m_scanCallback_postLollipop;
	}
	
	@Override protected double getInitialTimeout()
	{
		return m_scanTime;
	}
	
	@Override public void execute()
	{
		//--- DRK > Because scanning has to be done on a separate thread, isExecutable() can return true
		//---		but then by the time we get here it can be false. isExecutable() is currently not thread-safe
		//---		either, thus we're doing the manual check in the native stack. Before 5.0 the scan would just fail
		//---		so we'd fail as we do below, but Android 5.0 makes this an exception for at least some phones (OnePlus One (A0001)).
		if( false == getManager().getNative().getAdapter().isEnabled() )
		{
			fail();
		}
		else
		{
			if( false == getManager().isLocationEnabledForScanning() )
			{
				if( true == getManager().isLocationEnabledForScanning_byRuntimePermissions() && false == getManager().isLocationEnabledForScanning_byOsServices() )
				{
					//--- DRK > Classic discovery still seems to work as long as we have permissions.
					//---		In other words if location services are off we can still do classic scanning.
					execute_classic();
				}
				else
				{
					//--- DRK > Most likely won't return anything, but doesn't hurt to try...if (when) there's a bug in the OS we'll still get scan results
					//--		even though we're not supposed to.
					execute_locationEnabledFlow();
				}
			}
			else
			{
				execute_locationEnabledFlow();
			}
		}
	}

	private void execute_locationEnabledFlow()
	{
		final boolean forcePreLollipopScan = getManager().m_config.forcePreLollipopScan;

		//--- DRK > This config option is deprecated, but it still needs to take precedence for backwards compatibility until v3.
		if( forcePreLollipopScan )
		{
			execute_preLollipop();
		}
		else
		{
			final BleScanMode scanMode = getManager().m_config.scanMode != null ? getManager().m_config.scanMode : BleScanMode.AUTO;

			if( scanMode == BleScanMode.AUTO )
			{
				final boolean isPhonePreLollipop =  false == Utils.isLollipop();

				if( isPhonePreLollipop )
				{
					execute_preLollipop();
				}
				else
				{
					execute_postLollipop();
				}
			}
			else if( scanMode == BleScanMode.CLASSIC )
			{
				execute_classic();
			}
			else if( scanMode == BleScanMode.PRE_LOLLIPOP )
			{
				execute_preLollipop();
			}
			else if( scanMode.isLollipopScanMode() )
			{
				execute_postLollipop();
			}
			else
			{
				getManager().ASSERT(false, "Unhandled BleScanMode: " + scanMode);
			}
		}
	}

	private void execute_classic()
	{
		tryClassicDiscovery(getIntent(), /*suppressUhOh=*/true);

		m_mode = Mode_CLASSIC;
	}

	private void execute_preLollipop()
	{
		m_mode = startNativeScan_preLollipop(getIntent());

		if( m_mode == Mode_NULL )
		{
			fail();
		}
	}

	private void execute_postLollipop()
	{
		m_mode = Mode_BLE;
		getManager().m_nativeStateTracker.append(BleManagerState.SCANNING, getIntent(), BleStatuses.GATT_STATUS_NOT_APPLICABLE);

		startNativeScan_postLollipop();
	}

	private void startNativeScan_postLollipop()
	{
		final BleScanMode scanMode_abstracted = getManager().m_config.scanMode;

		final int scanMode;

		if( scanMode_abstracted == null || scanMode_abstracted == BleScanMode.AUTO )
		{
			if( getManager().isForegrounded() )
			{
				if( m_isPoll || m_scanTime == Double.POSITIVE_INFINITY )
				{
					scanMode = ScanSettings.SCAN_MODE_BALANCED;
				}
				else
				{
					scanMode = ScanSettings.SCAN_MODE_LOW_LATENCY;
				}
			}
			else
			{
				scanMode = ScanSettings.SCAN_MODE_LOW_POWER;
			}
		}
		else
		{
			scanMode = scanMode_abstracted.getNativeMode();
		}

		if( false == Utils.isLollipop() )
		{
			getManager().ASSERT(false, "Tried to create ScanSettings for pre-lollipop!");

			fail();
		}
		else
		{
			if( Utils.isMarshmallow() )
			{
				M_Util.startNativeScan(getManager(), scanMode, getManager().m_config.scanReportDelay, m_scanCallback_postLollipop);
			} else {
				L_Util.startNativeScan(getManager(), scanMode, getManager().m_config.scanReportDelay, m_scanCallback_postLollipop);
			}
		}
	}

	private int/*_Mode*/ startNativeScan_preLollipop(final E_Intent intent)
	{
		//--- DRK > Not sure how useful this retry loop is. I've definitely seen startLeScan
		//---		fail but then work again at a later time (seconds-minutes later), so
		//---		it's possible that it can recover although I haven't observed it in this loop.
		int retryCount = 0;

		while( retryCount <= m_retryCountMax )
		{
			final boolean success = getManager().getNativeAdapter().startLeScan(getManager().m_listeners.m_scanCallback_preLollipop);

			if( success )
			{
				if( retryCount >= 1 )
				{
					//--- DRK > Not really an ASSERT case...rather just really want to know if this can happen
					//---		so if/when it does I want it to be loud.
					//---		UPDATE: Yes, this hits...TODO: Now have to determine if this is my fault or Android's.
					//---		Error message is "09-29 16:37:11.622: E/BluetoothAdapter(16286): LE Scan has already started".
					//---		Calling stopLeScan below "fixes" the issue.
					//---		UPDATE: Seems like it mostly happens on quick restarts of the app while developing, so
					//---		maybe the scan started in the previous app sessions is still lingering in the new session.
//					ASSERT(false, "Started Le scan on attempt number " + retryCount);
				}

				break;
			}

			retryCount++;

			if( retryCount <= m_retryCountMax )
			{
				if( retryCount == 1 )
				{
					getLogger().w("Failed first startLeScan() attempt. Calling stopLeScan() then trying again...");

					//--- DRK > It's been observed that right on app start up startLeScan can fail with a log
					//---		message saying it's already started...not sure if it's my fault or not but throwing
					//---		this in as a last ditch effort to "fix" things.
					//---
					//---		UPDATE: It's been observed through simple test apps that when restarting an app through eclipse,
					//---		Android somehow, sometimes, keeps the same actual BleManager instance in memory, so it's not
					//---		far-fetched to assume that the scan from the previous app run can sometimes still be ongoing.
					//m_btMngr.getAdapter().stopLeScan(m_listeners.m_scanCallback);
					getManager().getNativeAdapter().stopLeScan(getManager().m_listeners.m_scanCallback_preLollipop);
				}
				else
				{
					getLogger().w("Failed startLeScan() attempt number " + retryCount + ". Trying again...");
				}
			}

//			try
//			{
//				Thread.sleep(10);
//			}
//			catch (InterruptedException e)
//			{
//			}
		}

		if( retryCount > m_retryCountMax )
		{
			getLogger().w("Pre-Lollipop LeScan totally failed to start!");

			tryClassicDiscovery(intent, /*suppressUhOh=*/false);

			return Mode_CLASSIC;
		}
		else
		{
			if( retryCount > 0 )
			{
				getLogger().w("Started native scan with " + (retryCount + 1) + " attempts.");
			}

			if( getManager().m_config.enableCrashResolver )
			{
				getManager().getCrashResolver().start();
			}

			getManager().m_nativeStateTracker.append(BleManagerState.SCANNING, intent, BleStatuses.GATT_STATUS_NOT_APPLICABLE);

			return Mode_BLE;
		}
	}

	private boolean tryClassicDiscovery(final E_Intent intent, final boolean suppressUhOh)
	{
		if( getManager().m_config.revertToClassicDiscoveryIfNeeded )
		{
			if( false == getManager().getNativeAdapter().startDiscovery() )
			{
				getLogger().w("Classic discovery failed to start!");

				fail();

				getManager().uhOh(BleManager.UhOhListener.UhOh.CLASSIC_DISCOVERY_FAILED);

				return false;
			}
			else
			{
				getManager().m_nativeStateTracker.append(BleManagerState.SCANNING, intent, BleStatuses.GATT_STATUS_NOT_APPLICABLE);

				if( false == suppressUhOh )
				{
					getManager().uhOh(BleManager.UhOhListener.UhOh.START_BLE_SCAN_FAILED__USING_CLASSIC);
				}

				return true;
			}
		}
		else
		{
			fail();

			getManager().uhOh(BleManager.UhOhListener.UhOh.START_BLE_SCAN_FAILED);

			return false;
		}
	}
	
	private double getMinimumScanTime()
	{
		return Interval.secs(getManager().m_config.idealMinScanTime);
	}
	
	@Override protected void update(double timeStep)
	{
		if( this.getState() == PE_TaskState.EXECUTING  )
		{
			if( getTotalTimeExecuting() >= getMinimumScanTime() && (getQueue().getSize() > 0 && isSelfInterruptableBy(getQueue().peek())) )
			{
				selfInterrupt();
			}
			else if( m_mode == Mode_CLASSIC && getTotalTimeExecuting() >= BleManagerConfig.MAX_CLASSIC_SCAN_TIME )
			{
				selfInterrupt();
			}
		}
	}
	
	@Override public PE_TaskPriority getPriority()
	{
		return PE_TaskPriority.TRIVIAL;
	}
	
	public int/*_Mode*/ getMode()
	{
		return m_mode;
	}

	private boolean isSelfInterruptableBy(final PA_Task otherTask)
	{
		if( otherTask.getPriority().ordinal() > PE_TaskPriority.TRIVIAL.ordinal() )
		{
			return true;
		}
		else if( otherTask.getPriority().ordinal() >= this.getPriority().ordinal() )
		{
			//--- DRK > Not sure infinite timeout check really matters here.
			return this.getTotalTimeExecuting() >= getMinimumScanTime();
//				return getTimeout() == TIMEOUT_INFINITE && this.getTotalTimeExecuting() >= getManager().m_config.minimumScanTime;
		}
		else
		{
			return false;
		}
	}
	
	@Override public boolean isInterruptableBy(PA_Task otherTask)
	{
		return otherTask.getPriority().ordinal() >= PE_TaskPriority.FOR_EXPLICIT_BONDING_AND_CONNECTING.ordinal();
	}
	
	@Override public boolean isExplicit()
	{
		return m_explicit;
	}
	
	@Override protected BleTask getTaskType()
	{
		return null;
	}
}
