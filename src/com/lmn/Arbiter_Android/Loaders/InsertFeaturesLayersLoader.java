package com.lmn.Arbiter_Android.Loaders;

import java.util.ArrayList;

import org.json.JSONArray;
import org.json.JSONException;

import android.app.Activity;
import android.content.Context;
import android.content.IntentFilter;
import android.database.sqlite.SQLiteDatabase;
import android.support.v4.content.AsyncTaskLoader;
import android.support.v4.content.LocalBroadcastManager;
import android.util.SparseArray;

import com.lmn.Arbiter_Android.ArbiterProject;
import com.lmn.Arbiter_Android.BaseClasses.BaseLayer;
import com.lmn.Arbiter_Android.BaseClasses.Layer;
import com.lmn.Arbiter_Android.BaseClasses.Server;
import com.lmn.Arbiter_Android.BroadcastReceivers.LayerBroadcastReceiver;
import com.lmn.Arbiter_Android.DatabaseHelpers.ApplicationDatabaseHelper;
import com.lmn.Arbiter_Android.DatabaseHelpers.FeatureDatabaseHelper;
import com.lmn.Arbiter_Android.DatabaseHelpers.ProjectDatabaseHelper;
import com.lmn.Arbiter_Android.DatabaseHelpers.TableHelpers.GeometryColumnsHelper;
import com.lmn.Arbiter_Android.DatabaseHelpers.TableHelpers.LayersHelper;
import com.lmn.Arbiter_Android.DatabaseHelpers.TableHelpers.PreferencesHelper;
import com.lmn.Arbiter_Android.DatabaseHelpers.TableHelpers.ServersHelper;
import com.lmn.Arbiter_Android.ProjectStructure.ProjectStructure;

public class InsertFeaturesLayersLoader extends AsyncTaskLoader<ArrayList<Layer>> {
	
	private LayerBroadcastReceiver loaderBroadcastReceiver = null;
	private ArrayList<Layer> layers;
	private Activity activity;
	private Context context;
	
	public InsertFeaturesLayersLoader(Activity activity) {
		super(activity.getApplicationContext());
		this.activity = activity;
		this.context = activity.getApplicationContext();
	}
	
	@Override
	public ArrayList<Layer> loadInBackground() {
		
		String projectName = ArbiterProject.getArbiterProject().getOpenProject(activity);
		
		String path = ProjectStructure.getProjectPath(projectName);
		
		SQLiteDatabase projectDb = getProjectDb(path);
		SQLiteDatabase featureDb = getFeatureDb(path);
		
		SQLiteDatabase appDb = getAppDb();
		
		ArrayList<Layer> layers = LayersHelper.getLayersHelper().
				getAll(projectDb);
		
		layers = removeLayersWithoutGeometryType(featureDb, layers);
		
		SparseArray<Server> servers = ServersHelper.getServersHelper().
				getAll(appDb);
		
		layers = addServerInfoToLayers(layers, servers);
		
		String json = PreferencesHelper.getHelper().get(projectDb, context, PreferencesHelper.BASE_LAYER);
		
		try {
			BaseLayer baseLayer = getBaseLayerFromJSON(json);
			
			layers = removeBaseLayerFromLayers(baseLayer, layers);
		} catch (JSONException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		
		return layers;
	}
	
	private ArrayList<Layer> removeLayersWithoutGeometryType(SQLiteDatabase featureDb, ArrayList<Layer> layers){
		
		Layer layer = null;
		
		for(int i = layers.size() - 1; i >= 0; i--){
			
			layer = layers.get(i);
			
			String geometryType = GeometryColumnsHelper.getHelper().getGeometryType(featureDb, layer.getFeatureTypeNoPrefix());
			
			if(geometryType == null){
				layers.remove(i);
			}
		}
		
		return layers;
	}
	
	private BaseLayer getBaseLayerFromJSON(String json) throws JSONException{
		
		if(json == null){
			return BaseLayer.createOSMBaseLayer();
		}
		
		JSONArray baseLayers = new JSONArray(json);
		
		return new BaseLayer(baseLayers.getJSONObject(0));
	}
	
	private ArrayList<Layer> removeBaseLayerFromLayers(BaseLayer baseLayer, ArrayList<Layer> layers){
		String featureType = baseLayer.getFeatureType();
		
		if(featureType == null || featureType.equals("null")){
			return layers;
		}
		
		for(int i = 0, count = layers.size(); i < count; i++){
			
			if(featureType.equals(layers.get(i).getFeatureType())){
				layers.remove(i);
				break;
			}
		}
		
		return layers;
	}
	
	protected ArrayList<Layer> addServerInfoToLayers(ArrayList<Layer> layers, 
			SparseArray<Server> servers){
		Server server;
		
		for(Layer layer : layers){
			server = servers.get(layer.getServerId());
			layer.setServerName(server.getName());
			layer.setServerUrl(server.getUrl());
		}
		
		return layers;
	}
	
	private SQLiteDatabase getFeatureDb(String projectPath){
		
		return FeatureDatabaseHelper.getHelper(context, projectPath, false).getWritableDatabase();
	}
	
	private SQLiteDatabase getProjectDb(String projectPath){
		
		return ProjectDatabaseHelper.getHelper(context, projectPath, false).getWritableDatabase();
	}
	
	protected SQLiteDatabase getAppDb(){
		
		return ApplicationDatabaseHelper.getHelper(context).getWritableDatabase();
	}
	
	/**
     * Called when there is new data to deliver to the client.  The
     * super class will take care of delivering it; the implementation
     * here just adds a little more logic.
     */
    @Override public void deliverResult(ArrayList<Layer> _layers) {
        if (isReset()) {
            // An async query came in while the loader is stopped.  We
            // don't need the result.
            if (layers != null) {
          //      onReleaseResources(cursor);
            }
        }
        
        ArrayList<Layer> oldLayers = layers;
        layers = _layers;

        if (isStarted()) {
            // If the Loader is currently started, we can immediately
            // deliver its results.
            super.deliverResult(layers);
        }

        // At this point we can release the resources associated with
        // 'oldApps' if needed; now that the new result is delivered we
        // know that it is no longer in use.
        if (oldLayers != null) {
            onReleaseResources(oldLayers);
        }
    }
    
    /**
     * Handles a request to start the Loader.
     */
    @Override protected void onStartLoading() {
        if (layers != null) {
            // If we currently have a result available, deliver it
            // immediately.
            deliverResult(layers);
        }

        // Start watching for changes in the app data.
        if (loaderBroadcastReceiver == null) {
        	loaderBroadcastReceiver = new LayerBroadcastReceiver(this);
        	LocalBroadcastManager.getInstance(getContext()).
        		registerReceiver(loaderBroadcastReceiver, new IntentFilter(LayersListLoader.LAYERS_LIST_UPDATED));
        }

        if (takeContentChanged() || layers == null) {
            // If the data has changed since the last time it was loaded
            // or is not currently available, start a load.
            forceLoad();
        }
    }

    /**
     * Handles a request to stop the Loader.
     */
    @Override protected void onStopLoading() {
        // Attempt to cancel the current load task if possible.
        cancelLoad();
    }

    /**
     * Handles a request to cancel a load.
     */
    @Override public void onCanceled(ArrayList<Layer> _layers) {
        super.onCanceled(_layers);

        // At this point we can release the resources associated with 'apps'
        // if needed.
        onReleaseResources(_layers);
    }

    /**
     * Handles a request to completely reset the Loader.
     */
    @Override protected void onReset() {
        super.onReset();

        // Ensure the loader is stopped
        onStopLoading();

        // At this point we can release the resources associated with 'apps'
        // if needed.
        if (layers != null) {
            onReleaseResources(layers);
            layers = null;
        }

        // Stop monitoring for changes.
        if (loaderBroadcastReceiver != null) {
        	LocalBroadcastManager.getInstance(getContext()).
        		unregisterReceiver(loaderBroadcastReceiver);
            loaderBroadcastReceiver = null;
        }
    }

    /**
     * Helper function to take care of releasing resources associated
     * with an actively loaded data set.
     */
    protected void onReleaseResources(ArrayList<Layer> _projects) {
        // For a simple List<> there is nothing to do.  For something
        // like a Cursor, we would close it here.
    	
    }
}
