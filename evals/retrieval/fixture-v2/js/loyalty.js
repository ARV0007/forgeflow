// FreshPoints: customers earn points on every order and can trade 500 points
// for money off a future shop.
const Loyalty = (() => {
  const POINTS_PER_POUND = 2;
  const POINTS_PER_REWARD = 500;
  const REWARD_VALUE_PENCE = 250;

  function pointsForOrder(orderPence) {
    return Math.floor(orderPence / 100) * POINTS_PER_POUND;
  }

  function addPoints(user, orderPence) {
    const balance = Storage.load('points:' + user.email, 0) + pointsForOrder(orderPence);
    Storage.save('points:' + user.email, balance);
    return balance;
  }

  function rewardsAvailable(balance) {
    return Math.floor(balance / POINTS_PER_REWARD);
  }

  function rewardValuePence(rewards) {
    return rewards * REWARD_VALUE_PENCE;
  }

  return { pointsForOrder, addPoints, rewardsAvailable, rewardValuePence };
})();
