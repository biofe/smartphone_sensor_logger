# Analysis

`first_ride.ipynb` checks the first test ride: whether sound and vibration data show a road sweeper, concrete slab joints and gravel. It draws stacked, scrollable overviews (sound and vibration heatmaps, sound level, vibration bands, speed, elevation) against both time and distance.

Put the exported ride ZIP (for example `rides_20261019-130306.zip`) in this folder, install `requirements.txt`, and run all cells. Ride ZIPs are git-ignored because they contain location traces. A floating map follows the cursor: hovering any time or distance plot draws a vertical line in all of them and moves a marker on the map (`ride_sync.js`). Known events can be entered in `EVENTS` (minutes) at the top so the overviews shade them.
