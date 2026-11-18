import { screen } from '@testing-library/react';

import { ReactModal } from '@spinnaker/core';

import { AzureRollbackServerGroupModal } from './RollbackServerGroupModal';

describe('AzureRollbackServerGroupModal', () => {
  const runtimeServices: any = { serverGroupWriter: { rollbackServerGroup: vi.fn() } };
  const application: any = { name: 'fnord', serverGroups: { refresh: vi.fn() } };
  const serverGroup: any = { name: 'azure-v002', account: 'test-account', region: 'westus' };
  const disabledServerGroups: any[] = [{ name: 'azure-v001', instanceCounts: { total: 2 }, capacity: { max: 2 } }];

  afterEach(() => {
    ReactModal.dismissAll();
  });

  it('renders the rollback form into the only modal layer', async () => {
    ReactModal.show(
      AzureRollbackServerGroupModal,
      { application, serverGroup, disabledServerGroups },
      {},
      runtimeServices,
    );

    const title = await screen.findByText('Rollback azure-v002');
    // A second full-screen `.modal` layer would cover this one and swallow every click.
    const modalLayers = Array.from(document.querySelectorAll('.modal'));

    expect(modalLayers).toHaveLength(1);
    expect(modalLayers[0]).toContainElement(title);
  });
});
