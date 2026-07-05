import TrackPlayer, { Event } from 'react-native-track-player';

export async function PlaybackService() {
  TrackPlayer.addEventListener(Event.RemoteDuck, async (event) => {
    if (event.permanent) {
      await TrackPlayer.stop();
    } else if (!event.paused) {
      await TrackPlayer.play();
    } else {
      await TrackPlayer.pause();
    }
  });
}
