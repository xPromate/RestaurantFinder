package ipp.estg.restaurantfinder.fragments;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.Location;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.SearchView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.recyclerview.widget.DividerItemDecoration;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.room.Room;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.tasks.OnFailureListener;
import com.google.android.gms.tasks.OnSuccessListener;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import ipp.estg.restaurantfinder.R;
import ipp.estg.restaurantfinder.activities.NearbyRestaurants;
import ipp.estg.restaurantfinder.adapters.RestaurantAdapter;
import ipp.estg.restaurantfinder.db.RestaurantDB;
import ipp.estg.restaurantfinder.db.RestaurantRoom;
import ipp.estg.restaurantfinder.helpers.RetrofitHelper;
import ipp.estg.restaurantfinder.interfaces.ZomatoApi;
import ipp.estg.restaurantfinder.models.Restaurants;
import ipp.estg.restaurantfinder.models.SearchResponse;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

import androidx.fragment.app.FragmentTransaction;

import static android.content.Context.MODE_PRIVATE;
import static ipp.estg.restaurantfinder.activities.PreferencesActivity.KEY_RADIUS;

public class RestaurantsList extends Fragment implements RestaurantAdapter.RestaurantAdapterListener {

    private Context context;
    private RestaurantAdapter restaurantAdapter;
    private RecyclerView recyclerView;
    private List<Restaurants> searchResponseList;
    private List<RestaurantRoom> favoriteRestaurantsList;
    private final ExecutorService databaseReadExecutor = Executors.newFixedThreadPool(1);
    private RestaurantDB db;
    private static final int REQUEST_FINE_LOCATION = 100;
    private FusedLocationProviderClient fusedLocationProviderClient;
    private String latitude;
    private String longitude;
    private SharedPreferences sharedPreferences;
    public static final String SHARED_PREF_NAME = "app_pref";
    public static final String KEY_RADIUS = "radius";
    private String radius;
    private SharedPreferences.Editor editor;
    private RestaurantsListFragmentListener listener;

    public interface RestaurantsListFragmentListener {
        void restaurantId(int id);
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        this.context = getActivity();
        this.favoriteRestaurantsList = new ArrayList<>();
        this.fusedLocationProviderClient = LocationServices.getFusedLocationProviderClient(context.getApplicationContext());
        setHasOptionsMenu(true);
        this.sharedPreferences = getActivity().getSharedPreferences(SHARED_PREF_NAME, MODE_PRIVATE);
        this.editor = sharedPreferences.edit();

        if (this.sharedPreferences.getString(KEY_RADIUS, null) == null) {
            this.editor.putString(KEY_RADIUS, "10000");
            this.editor.apply();
        }

        this.radius = this.sharedPreferences.getString(KEY_RADIUS, "");

        try {
            this.getLastLocation();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View contentView = inflater.inflate(R.layout.restaurants_fragment, container, false);

        this.recyclerView = contentView.findViewById(R.id.restaurantsRecyclerView);
        this.recyclerView.setLayoutManager(new LinearLayoutManager(contentView.getContext()));
        this.getRestaurants();
        this.restaurantAdapter = new RestaurantAdapter(this.context, new ArrayList<>(), this.favoriteRestaurantsList, this);
        this.recyclerView.setAdapter(this.restaurantAdapter);
        this.recyclerView.addItemDecoration(new DividerItemDecoration(this.context, DividerItemDecoration.VERTICAL));
        this.recyclerView.setLayoutManager(new LinearLayoutManager(this.context));

        return contentView;
    }

    @Override
    public void onCreateOptionsMenu(@NonNull Menu menu, @NonNull MenuInflater inflater) {
        inflater.inflate(R.menu.restaurant_menu, menu);

        MenuItem searchItem = menu.findItem(R.id.action_search);
        SearchView searchView = (SearchView) searchItem.getActionView();

        searchView.setOnQueryTextListener(new SearchView.OnQueryTextListener() {
            @Override
            public boolean onQueryTextSubmit(String s) {
                return false;
            }

            @Override
            public boolean onQueryTextChange(String s) {
                restaurantAdapter.getFilter().filter(s);
                return false;
            }
        });
    }

    private void getRestaurants() {
        db = Room.databaseBuilder(context, RestaurantDB.class, "RestaurantsDB").build();
        databaseReadExecutor.execute(() -> {
            favoriteRestaurantsList.addAll(Arrays.asList(db.daoAccess().getAll()));
        });
    }

    private void getLastLocation() throws InterruptedException {
        if (ActivityCompat.checkSelfPermission(this.context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions();
            return;
        }

        this.fusedLocationProviderClient.getLastLocation()
                .addOnSuccessListener(getActivity(), new OnSuccessListener<Location>() {
                    @Override
                    public void onSuccess(Location location) {
                        if (location != null) {
                            latitude = String.valueOf(location.getLatitude());
                            longitude = String.valueOf(location.getLongitude());

                            getRestaurantsFromAPI(latitude, longitude, Integer.parseInt(radius));
                        }
                    }
                }).addOnFailureListener(getActivity(), new OnFailureListener() {
            @Override
            public void onFailure(@NonNull Exception e) {
                Toast.makeText(context, "Couldn't get your location. Try again later!", Toast.LENGTH_LONG).show();
            }
        });
    }

    private void requestPermissions() throws InterruptedException {
        ActivityCompat.requestPermissions(getActivity(), new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, REQUEST_FINE_LOCATION);

        if (ActivityCompat.checkSelfPermission(this.context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            Thread.sleep(1000);
        }

        getActivity().recreate();
    }

    private void getRestaurantsFromAPI(String latitude, String longitude, int radius) {
        ZomatoApi zomatoapi = RetrofitHelper.getRetrofit().create(ZomatoApi.class);

        //Call<SearchResponse> call = zomatoapi.getNearbyRestaurantsDistance(latitude, longitude, String.valueOf(radius), "real_distance");

        Call<SearchResponse> call = zomatoapi.getNearbyRestaurantsDistance(latitude, longitude, String.valueOf(radius),"real_distance");
        this.searchResponseList = new ArrayList<>();

        call.enqueue(new Callback<SearchResponse>() {
            @Override
            public void onResponse(Call<SearchResponse> call, Response<SearchResponse> response) {
                if (response.isSuccessful()) {
                    searchResponseList.addAll(response.body().getRestaurants());
                    restaurantAdapter.setRestaurants(searchResponseList);

                    getActivity().findViewById(R.id.loadingPanel).setVisibility(View.GONE);
                }
            }

            @Override
            public void onFailure(Call<SearchResponse> call, Throwable t) {
                Toast.makeText(context, "Error fetching data from Zomato, please try again later!", Toast.LENGTH_LONG).show();
            }
        });
    }

    @Override
    public void onClick(int position) {
        this.listener.restaurantId(position);
        //Toast.makeText(getContext(), "" + position, Toast.LENGTH_LONG).show();
    }

    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);

        if(context instanceof RestaurantsListFragmentListener) {
            listener = (RestaurantsListFragmentListener) context;
        } else {
            throw new RuntimeException(context.toString() + " tem de implementar a interface");
        }
    }

    @Override
    public void onDetach() {
        super.onDetach();
        listener = null;
    }
}
