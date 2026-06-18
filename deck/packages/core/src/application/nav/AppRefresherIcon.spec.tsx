import { mock } from 'angular';
import { mount } from 'enzyme';
import React from 'react';

import { AppRefresherIcon } from './AppRefresherIcon';
import { REACT_MODULE } from '../../reactShims';
import type { IScheduler } from '../../scheduler';
import { SchedulerFactory } from '../../scheduler';

describe('<AppRefresherIcon/>', () => {
  beforeEach(mock.module(REACT_MODULE));
  beforeEach(mock.inject(() => {})); // Angular is lazy.

  function makeFakeScheduler(): IScheduler {
    return {
      subscribe: jasmine.createSpy('subscribe'),
      scheduleImmediate: jasmine.createSpy('scheduleImmediate'),
      unsubscribe: jasmine.createSpy('unsubscribe'),
    } as any;
  }

  it('creates a single scheduler and does not create another on a lastRefresh-stable re-render', () => {
    const createScheduler = spyOn(SchedulerFactory, 'createScheduler').and.returnValue(makeFakeScheduler());

    const wrapper = mount(
      <AppRefresherIcon appName="myapp" lastRefresh={1000} refreshing={false} refresh={() => undefined} />,
    );

    // The scheduler (an rxjs timer plus visibilitychange/online/offline listeners) is created by the
    // effect, not in the render body, so exactly one exists after mount.
    expect(createScheduler).toHaveBeenCalledTimes(1);

    // A re-render that does NOT change lastRefresh (the refresh state toggling on click, a
    // `refreshing` prop change, or a parent re-render) must not spin up another scheduler, otherwise
    // its timer and listeners leak for the rest of the session.
    wrapper.setProps({ refreshing: true });
    expect(createScheduler).toHaveBeenCalledTimes(1);

    wrapper.unmount();
  });

  it('tears down the scheduler on unmount', () => {
    const fakeScheduler = makeFakeScheduler();
    spyOn(SchedulerFactory, 'createScheduler').and.returnValue(fakeScheduler);

    const wrapper = mount(
      <AppRefresherIcon appName="myapp" lastRefresh={1000} refreshing={false} refresh={() => undefined} />,
    );
    expect(fakeScheduler.unsubscribe).not.toHaveBeenCalled();

    wrapper.unmount();
    expect(fakeScheduler.unsubscribe).toHaveBeenCalledTimes(1);
  });
});
