import React from 'react';

import { DeckRuntimeContext, InfrastructureCaches, NetworkReader } from '@spinnaker/core';

import { AzureWizardPage } from './common';
import Utility from '../../../../utility';

function normalizeLoadBalancerType(loadBalancerType: string): string | null {
  const normalized = String(loadBalancerType || '')
    .toLowerCase()
    .split('_')
    .join(' ');
  if (normalized === 'application gateway') {
    return 'Azure Application Gateway';
  }
  if (normalized === 'load balancer') {
    return 'Azure Load Balancer';
  }
  return Utility.getLoadBalancerType(loadBalancerType)?.type || loadBalancerType || null;
}

function selectableSubnetNames(vnet: any): string[] {
  return (vnet?.subnets || [])
    .filter((subnet: any) =>
      (subnet.devices || []).every((device: any) => !device || device.type !== 'applicationGateways'),
    )
    .map((subnet: any) => subnet.name || subnet);
}

function matchesSelectedVnet(candidate: any, selectedVnet: any): boolean {
  return (
    candidate.name === selectedVnet.name &&
    (!selectedVnet.resourceGroup || candidate.resourceGroup === selectedVnet.resourceGroup)
  );
}

export class ServerGroupLoadBalancers extends AzureWizardPage {
  public static contextType = DeckRuntimeContext;
  public declare context: React.ContextType<typeof DeckRuntimeContext>;

  private loadVnetSubnetsRequestId = 0;

  // Load balancers may live outside the application's own resource group. Only
  // the default group's are offered until the user asks for all of them, since
  // listing every load balancer in the subscription is slow.
  private availableResourceGroups: string[] = [];
  private allLoadBalancersLoaded = false;
  private refreshing = false;

  private defaultResourceGroup(): string {
    const { application, region } = this.props.formik.values;
    return `${application}-${String(region || '')
      .replace(/\s/g, '')
      .toLowerCase()}`;
  }

  private loadBalancersInResourceGroup(resourceGroup: string | null): string[] {
    const { values } = this.props.formik;
    const loadBalancers = values.backingData?.loadBalancers || [];
    const names = loadBalancers
      .filter(
        (candidate: any) =>
          candidate.account === values.credentials &&
          candidate.region === values.region &&
          (!resourceGroup || !candidate.resourceGroup || candidate.resourceGroup === resourceGroup),
      )
      .map((candidate: any) => candidate.name);
    return Array.from(new Set<string>(names)).sort();
  }

  private applyResourceGroupFilter(resourceGroup: string | null): void {
    this.setField('loadBalancers', this.loadBalancersInResourceGroup(resourceGroup));
  }

  private resourceGroupChanged = (resourceGroup: string): void => {
    this.setField('loadBalancerResourceGroup', resourceGroup || null);
    this.setField('backendPoolName', null);
    this.applyResourceGroupFilter(resourceGroup || null);
    void this.loadBalancerChanged(null, true);
  };

  private showAllLoadBalancers = async (): Promise<void> => {
    this.refreshing = true;
    this.forceUpdate();
    try {
      const summaries: any[] = await this.context.services.loadBalancerReader.listLoadBalancers('azure');
      const flattened = summaries.flatMap((summary: any) =>
        (summary.accounts || []).flatMap((account: any) =>
          (account.regions || []).flatMap((region: any) => region.loadBalancers || []),
        ),
      );
      this.setField('backingData.loadBalancers', flattened);
      const { values } = this.props.formik;
      const groups = flattened
        .filter(
          (candidate: any) =>
            candidate.account === values.credentials && candidate.region === values.region && candidate.resourceGroup,
        )
        .map((candidate: any) => candidate.resourceGroup);
      const unique = Array.from(new Set<string>(groups)).sort();
      const defaultGroup = this.defaultResourceGroup();
      this.availableResourceGroups = unique.includes(defaultGroup) ? unique : [defaultGroup, ...unique];
      this.applyResourceGroupFilter(values.loadBalancerResourceGroup || null);
      this.allLoadBalancersLoaded = true;
    } finally {
      this.refreshing = false;
      this.forceUpdate();
    }
  };

  private getCommandLoadBalancer(loadBalancerName: string | null): any {
    const { values } = this.props.formik;
    const loadBalancers = values.backingData?.loadBalancers || [];
    return (
      loadBalancers.find(
        (candidate: any) =>
          candidate.name === loadBalancerName &&
          candidate.account === values.credentials &&
          candidate.region === values.region,
      ) || loadBalancers.find((candidate: any) => candidate.name === loadBalancerName)
    );
  }

  public componentDidMount(): void {
    const { values } = this.props.formik;
    if (values.credentials && values.region) {
      values.viewState.networkSettingsConfigured = true;
      values.selectedVnetSubnets = values.selectedVnetSubnets || [];
      const resourceGroup = values.loadBalancerResourceGroup || this.defaultResourceGroup();
      if (!values.loadBalancerResourceGroup) {
        this.setField('loadBalancerResourceGroup', resourceGroup);
      }
      this.availableResourceGroups = [resourceGroup];
      this.applyResourceGroupFilter(resourceGroup);
      if (values.loadBalancerName) {
        void this.loadBalancerChanged(values.loadBalancerName);
      } else {
        void this.loadVnetSubnets(null, values.loadBalancerType || null, ++this.loadVnetSubnetsRequestId);
      }
    }
  }

  public componentWillUnmount(): void {
    this.loadVnetSubnetsRequestId += 1;
  }

  public loadBalancerChanged = async (loadBalancerName: string | null, clearNetworkFields = false): Promise<void> => {
    const { values } = this.props.formik;
    const requestId = ++this.loadVnetSubnetsRequestId;
    const loadBalancer = this.getCommandLoadBalancer(loadBalancerName);
    const loadBalancerType = normalizeLoadBalancerType(loadBalancer?.loadBalancerType);
    values.loadBalancerName = loadBalancerName || null;
    values.loadBalancerType = loadBalancerType;
    values.selectedVnetSubnets = [];
    if (clearNetworkFields) {
      values.selectedVnet = null;
      values.vnet = null;
      values.vnetResourceGroup = null;
      values.selectedSubnet = null;
      values.subnet = null;
    }
    values.viewState = values.viewState || {};
    values.viewState.networkSettingsConfigured = true;
    this.props.formik.setFieldValue('loadBalancerName', values.loadBalancerName);
    this.props.formik.setFieldValue('loadBalancerType', values.loadBalancerType);
    this.props.formik.setFieldValue('selectedVnetSubnets', []);
    if (clearNetworkFields) {
      this.props.formik.setFieldValue('selectedVnet', null);
      this.props.formik.setFieldValue('vnet', null);
      this.props.formik.setFieldValue('vnetResourceGroup', null);
      this.props.formik.setFieldValue('selectedSubnet', null);
      this.props.formik.setFieldValue('subnet', null);
    }
    InfrastructureCaches.clearCache('networks');
    await this.loadVnetSubnets(values.loadBalancerName, loadBalancerType, requestId);
  };

  private loadVnetSubnets = async (
    loadBalancerName: string | null,
    loadBalancerType: string | null,
    requestId: number,
  ): Promise<void> => {
    const { values } = this.props.formik;
    const credentials = values.credentials;
    const region = values.region;
    if (!credentials || !region) {
      return;
    }

    let loadBalancerDetails: any[];
    let networks: any;
    try {
      [loadBalancerDetails, networks] = await Promise.all([
        loadBalancerName
          ? this.context.services.loadBalancerReader.getLoadBalancerDetails(
              'azure',
              credentials,
              region,
              loadBalancerName,
            )
          : Promise.resolve([]),
        NetworkReader.listNetworks(),
      ]);
    } catch (_error) {
      return;
    }
    if (
      requestId !== this.loadVnetSubnetsRequestId ||
      values.loadBalancerName !== loadBalancerName ||
      values.credentials !== credentials ||
      values.region !== region
    ) {
      return;
    }
    const azureNetworks = Array.isArray(networks) ? networks : (networks as any).azure || [];
    const allVnets = azureNetworks.filter((vnet: any) => vnet.account === credentials && vnet.region === region);
    const attachedVnet = values.selectedVnet;
    const selectedLoadBalancer = loadBalancerDetails?.length === 1 ? loadBalancerDetails[0] : null;
    let selectedVnet =
      !selectedLoadBalancer && attachedVnet
        ? allVnets.find((vnet: any) => matchesSelectedVnet(vnet, attachedVnet)) || null
        : null;

    if (selectedLoadBalancer) {
      selectedVnet = allVnets.find((vnet: any) => {
        if (loadBalancerType === 'Azure Application Gateway') {
          return vnet.name === selectedLoadBalancer.vnet;
        }
        if (loadBalancerType === 'Azure Load Balancer' && attachedVnet) {
          return matchesSelectedVnet(vnet, attachedVnet);
        }
        return false;
      });
    }

    const selectedVnetSubnets = selectedVnet
      ? selectableSubnetNames(selectedVnet)
      : allVnets.reduce((subnets: string[], vnet: any) => subnets.concat(selectableSubnetNames(vnet)), []);

    values.allVnets = allVnets;
    values.selectedVnet = selectedVnet || null;
    values.vnet = selectedVnet?.name || null;
    values.vnetResourceGroup = selectedVnet?.resourceGroup || null;
    values.selectedVnetSubnets = selectedVnetSubnets;
    values.selectedSubnet = selectedVnet && selectedVnetSubnets.includes(values.subnet) ? values.subnet : null;
    values.subnet = values.selectedSubnet;

    this.props.formik.setFieldValue('allVnets', allVnets);
    this.props.formik.setFieldValue('selectedVnet', values.selectedVnet);
    this.props.formik.setFieldValue('vnet', values.vnet);
    this.props.formik.setFieldValue('vnetResourceGroup', values.vnetResourceGroup);
    this.props.formik.setFieldValue('selectedVnetSubnets', selectedVnetSubnets);
    this.props.formik.setFieldValue('selectedSubnet', values.selectedSubnet);
    this.props.formik.setFieldValue('subnet', values.subnet);
  };

  public render() {
    const loadBalancers =
      this.props.formik.values.loadBalancers || this.props.formik.values.backingData?.filtered?.loadBalancers || [];
    return (
      <div className="container-fluid form-horizontal">
        <div className="form-group">
          <div className="col-md-3 sm-label-right">Resource Group</div>
          <div className="col-md-7">
            <select
              className="form-control input-sm"
              onChange={(event) => this.resourceGroupChanged(event.target.value)}
              value={this.props.formik.values.loadBalancerResourceGroup || ''}
            >
              {this.availableResourceGroups.map((resourceGroup) => (
                <option key={resourceGroup} value={resourceGroup}>
                  {resourceGroup}
                </option>
              ))}
            </select>
          </div>
        </div>
        <div className="form-group">
          <div className="col-md-3 sm-label-right">Load Balancer</div>
          <div className="col-md-7">
            <select
              className="form-control input-sm"
              onChange={(event) => this.loadBalancerChanged(event.target.value, true)}
              value={this.props.formik.values.loadBalancerName || ''}
            >
              <option value="">None</option>
              {loadBalancers.map((loadBalancer: string) => (
                <option key={loadBalancer} value={loadBalancer}>
                  {loadBalancer}
                </option>
              ))}
            </select>
          </div>
        </div>
        {!this.allLoadBalancersLoaded && (
          <div className="form-group">
            <div className="col-md-7 col-md-offset-3 small">
              {this.refreshing ? (
                <span>
                  <span className="fa fa-sync-alt fa-spin" /> refreshing...
                </span>
              ) : (
                <span>
                  If you are looking for a load balancer from a different resource group,{' '}
                  <a className="clickable" onClick={this.showAllLoadBalancers}>
                    click here
                  </a>{' '}
                  to load all load balancers.
                </span>
              )}
            </div>
          </div>
        )}
        {this.props.formik.values.loadBalancerName && (
          <div className="well-compact text-center">
            The load balancer {this.props.formik.values.loadBalancerName} is an{' '}
            {this.props.formik.values.loadBalancerType}
          </div>
        )}
        {this.props.formik.values.loadBalancerName && (
          <div className="form-group">
            <div className="col-md-3 sm-label-right">Backend Pool</div>
            <div className="col-md-7">
              <input
                className="form-control input-sm"
                onChange={(event) => this.setField('backendPoolName', event.target.value)}
                type="text"
                value={this.props.formik.values.backendPoolName || ''}
              />
              <div className="small text-muted">
                Name of the backend address pool to place the server group into. If empty, no pool association is made.
              </div>
            </div>
          </div>
        )}
      </div>
    );
  }
}
