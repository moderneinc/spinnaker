import { mock } from 'angular';
import { shallow } from 'enzyme';
import React from 'react';

import { AppRefresherIcon } from './AppRefresherIcon';
import { REACT_MODULE } from '../../reactShims';
import { SchedulerFactory } from '../../scheduler';

describe('<AppRefresherIcon/>', () => {
  beforeEach(mock.module(REACT_MODULE));
  beforeEach(mock.inject(() => {})); // Angular is lazy.

  it('does not create a new scheduler when re-rendering without a lastRefresh change', () => {
    const fakeScheduler = {
      subscribe: jasmine.createSpy('subscribe'),
      scheduleImmediate: jasmine.createSpy('scheduleImmediate'),
      unsubscribe: jasmine.createSpy('unsubscribe'),
    };
    const createScheduler = spyOn(SchedulerFactory, 'createScheduler').and.returnValue(fakeScheduler as any);

    const wrapper = shallow(
      <AppRefresherIcon appName="myapp" lastRefresh={1000} refreshing={false} refresh={() => undefined} />,
    );

    // Ignore any scheduler created during the initial render; we only care about re-renders.
    createScheduler.calls.reset();

    // A re-render that does NOT change lastRefresh (e.g. the refresh state toggling on click,
    // a `refreshing` prop change, or a parent re-render) must not spin up another scheduler.
    // Each scheduler registers an rxjs timer plus visibilitychange/online/offline listeners that
    // are only torn down via unsubscribe(), so creating one per render leaks them for the session.
    wrapper.setProps({ refreshing: true });

    expect(createScheduler).not.toHaveBeenCalled();
  });
});
